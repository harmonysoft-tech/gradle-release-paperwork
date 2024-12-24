package tech.harmonysoft.oss.gradle.release.paperwork

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.fail
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.diff.RawTextComparator
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.util.io.DisabledOutputStream
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.LocalDate
import java.util.*

internal class GradleReleasePaperworkPluginTest {

    @TempDir private lateinit var rootProjectDir: File
    private lateinit var gradleFile: File
    private lateinit var git: Git
    private val commit1message = "feature1"
    private val dateTimeUtc = LocalDate
        .parse("2022-10-21")
        .atStartOfDay()
        .atZone(GradleReleasePaperworkPlugin.ZONE_UTC)

    @BeforeEach
    fun setUp() {
        prepareGradleFile()
        git = createGitRepo()
        makeCommit(commit1message)
        System.setProperty(GradleReleasePaperworkPlugin.DATE_SYSTEM_PROPERTY_NAME, dateTimeUtc.toLocalDate().toString())
    }

    private fun prepareGradleFile() {
        gradleFile = File(rootProjectDir, "build.gradle.kts")
        gradleFile.writeText("""
            plugins {
              id("tech.harmonysoft.oss.gradle.release.paperwork")
            }
            
        """.trimIndent())
    }

    private fun createGitRepo(): Git {
        return Git.init().setDirectory(rootProjectDir).call()
    }

    private fun makeCommit(message: String): RevCommit {
        val rnd = UUID.randomUUID().toString()
        val file = File(rootProjectDir, rnd)
        file.writeText(rnd)
        git.add().addFilepattern(rnd).call()
        return git.commit().setMessage(message).call()
    }

    private fun getCommit(commitMessage: String): RevCommit {
        val commits = git.log().call().toList()
        return commits.find {
            it.shortMessage == commitMessage
        } ?: fail(
            "can't find a commit with message '$commitMessage', ${commits.size} commits are available: " +
            commits.map { it.shortMessage }
        )
    }

    private fun getReleaseDescription(version: String, additionalDescription: String? = null): String {
        return GradleReleasePaperworkPlugin.getReleaseDescription(version, dateTimeUtc, additionalDescription)
    }

    private fun getCommitDescriptionInNotes(commitMessage: String): String {
        val commit = getCommit(commitMessage)
        return "${commit.id.name} $commitMessage"
    }

    private fun runBuild(): BuildResult {
        return GradleRunner.create()
            .withProjectDir(rootProjectDir)
            .withArguments("release-paperwork", "--stacktrace")
            .withPluginClasspath()
            .withDebug(true)
            .build()
    }

    private fun verifyFailure(expectedText: String) {
        try {
            runBuild()
            fail("the build was expected to fail with error '$expectedText' but it was successful")
        } catch (e: Exception) {
            assertThat(e.message).contains(expectedText)
        }
    }

    private fun verifyReleaseNotes(
        expectedText: String,
        releaseNotesFilePath: String = GradleReleasePaperworkPlugin.DEFAULT_RELEASE_NOTES_FILE
    ) {
        verifyFileContent(expectedText, File(rootProjectDir, releaseNotesFilePath))
    }

    private fun verifyFileContent(expectedText: String, file: File) {
        assertThat(file.readText().trim()).isEqualTo(expectedText.trim())
    }

    private fun verifyFileCommitted(file: File) {
        val walk = RevWalk(git.repository)
        val head = git.repository.resolve(Constants.HEAD)
        val headCommit = walk.parseCommit(head)
        val parentCommit = walk.parseCommit(headCommit.getParent(0).id)
        val diffFormatter = DiffFormatter(DisabledOutputStream.INSTANCE)
        diffFormatter.setRepository(git.repository)
        diffFormatter.setDiffComparator(RawTextComparator.DEFAULT)
        val paths = diffFormatter.scan(parentCommit.tree, headCommit.tree).map {
            it.newPath
        }

        val expectedPath = rootProjectDir.toPath().relativize(file.toPath())
        assertThat(paths).contains(expectedPath.toString())
    }

    @Test
    fun `when no version is defined in gradle file then the build fails`() {
        verifyFailure("can't extract project version")
    }

    @Test
    fun `when custom version file doesn't exist then the build fails`() {
        gradleFile.appendText("""
             version = "1.0.0"
             
             releasePaperwork {
                 projectVersionFile.set("xxx")
             }
        """.trimIndent())
        verifyFailure("project version file doesn't exist")
    }

    @Test
    fun `when current version can't be extracted using custom regex from default file then the build fails`() {
        gradleFile.appendText("""
             version = "1.0.0"
             
             releasePaperwork {
                 projectVersionRegex.set("(\\d\\d\\d\\d\\d\\d\\d)")
             }
        """.trimIndent())
        verifyFailure("can't extract project version")
    }

    @Test
    fun `when no version is released yet then single commit goes into release notes`() {
        val version = "1.0.0"
        gradleFile.appendText("""version = "$version"""")
        runBuild()
        verifyReleaseNotes("""
            ${getReleaseDescription(version)}
              * ${getCommitDescriptionInNotes(commit1message)}

        """.trimIndent())
    }

    @Test
    fun `when custom additional release description is defined then it's respected`() {
        val version = "1.0.0"
        val additionalDescription = ", custom message"
        gradleFile.appendText("""
            version = "$version"
            
            releasePaperwork {
                additionalReleaseDescription.set { "$additionalDescription" }
            }
        """.trimIndent())
        runBuild()
        verifyReleaseNotes("""
            ${getReleaseDescription(version, additionalDescription)}
              * ${getCommitDescriptionInNotes(commit1message)}

        """.trimIndent())
    }

    @Test
    fun `when custom change description info is defined then it's respected`() {
        val version = "1.0.0"
        val customDescription = "custom description"
        gradleFile.appendText("""
            version = "$version"

            releasePaperwork {
                changeDescription.set { "$customDescription" }
            }
        """.trimIndent())
        makeCommit("feature2")
        runBuild()
        verifyReleaseNotes("""
            ${getReleaseDescription(version)}
              * ${getCommit("feature2").id.name} $customDescription
              * ${getCommit(commit1message).id.name} $customDescription
        """.trimIndent())
    }

    @Test
    fun `when commit is excluded from release by custom logic then it's respected`() {
        val version = "1.0.0"
        val commit2message = "feature2"
        val commit3message = "feature3"
        gradleFile.appendText("""
            version = "$version"

            releasePaperwork {
                changeDescription.set { commitMessage ->
                    commitMessage.takeUnless { it == "$commit2message" }
                }
            }
        """.trimIndent())
        makeCommit(commit2message)
        makeCommit(commit3message)
        runBuild()
        verifyReleaseNotes("""
            ${getReleaseDescription(version)}
              * ${getCommitDescriptionInNotes("feature3")}
              * ${getCommitDescriptionInNotes(commit1message)}
        """.trimIndent())
    }

    @Test
    fun `when multiline commit message is encountered then it's collapsed into single line`() {
        val version = "1.0.0"
        gradleFile.appendText("""
            version = "$version"
        """.trimIndent())
        val commit2 = makeCommit("""
            feature2 line1
            line2
        """.trimIndent())
        runBuild()
        verifyReleaseNotes("""
            ${getReleaseDescription(version)}
              * ${commit2.id.name} feature2 line1 line2
              * ${getCommitDescriptionInNotes(commit1message)}

        """.trimIndent())
    }

    @Test
    fun `when all commits are excluded by custom logic then no release is generated`() {
        gradleFile.appendText("""
            version = "1.0.0"

            releasePaperwork {
                changeDescription.set { null }
            }
        """.trimIndent())
        makeCommit("feature2")
        runBuild()
        assertThat(File(rootProjectDir, GradleReleasePaperworkPlugin.DEFAULT_RELEASE_NOTES_FILE).isFile).isFalse
    }

    @Test
    fun `when now is not the first release then new release has correct data`() {
        gradleFile.appendText("""
            version = "1.0.0"
        """.trimIndent())
        makeCommit("feature2")
        makeCommit("feature3")
        val releaseNotesFile = File(rootProjectDir, GradleReleasePaperworkPlugin.DEFAULT_RELEASE_NOTES_FILE)
        releaseNotesFile.writeText("""
            ${getReleaseDescription("1.0.0")}
              * ${getCommitDescriptionInNotes(commit1message)}
        """.trimIndent())

        runBuild()

        verifyReleaseNotes("""
            ${getReleaseDescription("1.1.0")}
              * ${getCommitDescriptionInNotes("feature3")}
              * ${getCommitDescriptionInNotes("feature2")}
            ${getReleaseDescription("1.0.0")}
              * ${getCommitDescriptionInNotes(commit1message)}
        """.trimIndent())
    }

    @Test
    fun `when there are unreleased changes and explicit new version is defined then it's used`() {
        val customVersion = "1.0.1"
        gradleFile.appendText("""
            version = "$customVersion"
        """.trimIndent())
        makeCommit("feature2")
        val releaseNotesFile = File(rootProjectDir, GradleReleasePaperworkPlugin.DEFAULT_RELEASE_NOTES_FILE)
        releaseNotesFile.writeText("""
            ${getReleaseDescription("1.0.0")}
              * ${getCommitDescriptionInNotes(commit1message)}
        """.trimIndent())

        runBuild()

        verifyReleaseNotes("""
            ${getReleaseDescription("1.0.1")}
              * ${getCommitDescriptionInNotes("feature2")}
            ${getReleaseDescription("1.0.0")}
              * ${getCommitDescriptionInNotes(commit1message)}
        """.trimIndent())
    }

    @Test
    fun `when file with release version is not in root dir then release is successful`() {
        val version = "1.21.0"
        val newVersion = "1.22.0"
        val buildSrcDir = File(rootProjectDir, "buildSrc").apply { mkdirs() }
        File(buildSrcDir, "build.gradle.kts").writeText("""
            plugins {
                `kotlin-dsl`
            }

            repositories {
                mavenCentral()
            }
        """.trimIndent())
        val buildSrcSourcesDir = File(buildSrcDir, "src/main/kotlin").apply { mkdirs() }
        val versionFile = File(buildSrcSourcesDir, "Version.kt")
        versionFile.writeText("""
            object Version {
                const val APP = "$version"
            }
        """.trimIndent())
        gradleFile.appendText("""
            version = Version.APP

            releasePaperwork {
                projectVersionFile.set("buildSrc/src/main/kotlin/Version.kt")
                projectVersionRegex.set("APP\\s+=\\s+\"([^\"]+)")
            }
        """.trimIndent())

        val releaseNotesFile = File(rootProjectDir, GradleReleasePaperworkPlugin.DEFAULT_RELEASE_NOTES_FILE)
        releaseNotesFile.writeText("""
            ${getReleaseDescription(version)}
              * ${getCommitDescriptionInNotes(commit1message)}
        """.trimIndent())

        makeCommit("feature2")

        runBuild()

        verifyReleaseNotes("""
            ${getReleaseDescription(newVersion)}
              * ${getCommitDescriptionInNotes("feature2")}
            ${getReleaseDescription(version)}
              * ${getCommitDescriptionInNotes(commit1message)}
        """.trimIndent())
        verifyFileContent("""
            object Version {
                const val APP = "$newVersion"
            }
        """.trimIndent(), versionFile)
        verifyFileCommitted(versionFile)
    }

    @Test
    fun `when multiple subsequent releases are done correct features are listed`() {
        gradleFile.appendText("""
            version = "1.0.0"
        """.trimIndent())
        runBuild()

        makeCommit("feature2")
        runBuild()
        verifyReleaseNotes("""
            ${GradleReleasePaperworkPlugin.getReleaseDescription("1.1.0", dateTimeUtc, null)}
              * ${getCommitDescriptionInNotes("feature2")}
            ${GradleReleasePaperworkPlugin.getReleaseDescription("1.0.0", dateTimeUtc, null)}
              * ${getCommitDescriptionInNotes(commit1message)}
        """.trimIndent())
    }

    @Test
    fun `when number of commits per release is exceeded then only the first commits are printed`() {
        gradleFile.appendText("""
            version = "1.0.0"
            
            releasePaperwork {
                maxChangesPerRelease.set(1)
            }
        """.trimIndent())
        makeCommit("feature2")
        runBuild()
        verifyReleaseNotes("""
            ${GradleReleasePaperworkPlugin.getReleaseDescription("1.0.0", dateTimeUtc, null)}
              * ${getCommitDescriptionInNotes("feature2")}
              * ...
        """.trimIndent())
    }

    @Test
    fun `when version is incremented by the plugin then the 'patch' version is reset`() {
        gradleFile.appendText("""
            version = "1.0.1"
        """.trimIndent())
        runBuild()

        makeCommit("feature2")
        runBuild()
        verifyReleaseNotes("""
            ${GradleReleasePaperworkPlugin.getReleaseDescription("1.1.0", dateTimeUtc, null)}
              * ${getCommitDescriptionInNotes("feature2")}
            ${GradleReleasePaperworkPlugin.getReleaseDescription("1.0.1", dateTimeUtc, null)}
              * ${getCommitDescriptionInNotes(commit1message)}
        """.trimIndent())
    }

    @Test
    fun `when a plugin with particular version is applied then its version is not overwritten during release`() {
        gradleFile.writeText("""
            plugins {
              id("tech.harmonysoft.oss.gradle.release.paperwork")
              kotlin("jvm") version "1.7.20"
            }
            
            version = "1.7.20"
        """.trimIndent())

        val releaseNotesFile = File(rootProjectDir, GradleReleasePaperworkPlugin.DEFAULT_RELEASE_NOTES_FILE)
        releaseNotesFile.writeText("""
            ${getReleaseDescription("1.7.20")}
              * ${getCommitDescriptionInNotes(commit1message)}
        """.trimIndent())

        makeCommit("feature2")
        runBuild()

        verifyFileContent("""
            plugins {
              id("tech.harmonysoft.oss.gradle.release.paperwork")
              kotlin("jvm") version "1.7.20"
            }
            
            version = "1.8.0"
        """.trimIndent(), gradleFile)
    }

    @Test
    fun `when groovy DSL is used with custom change description then it works fine`() {
        gradleFile.delete()
        val buildGradle = File(rootProjectDir, "build.gradle")
        buildGradle.writeText("""
            plugins {
              id "tech.harmonysoft.oss.gradle.release.paperwork"
            }

            releasePaperwork {
                changeDescription.set(new kotlin.jvm.functions.Function1<String, String>() {

                    private final java.util.regex.Pattern mergePattern = java.util.regex.Pattern.compile(
                        "[Mm]erged branch '[^']+' into '[^']+'"
                    )
                
                    String invoke(String description) {
                        return mergePattern.matcher(description).replaceAll("").trim()
                    }
                })
            }

            version = '1.0.0'
        """.trimIndent())

        makeCommit("Merged branch 'bla' into 'bla-bla'")

        runBuild()

        verifyReleaseNotes("""
            ${GradleReleasePaperworkPlugin.getReleaseDescription("1.0.0", dateTimeUtc, null)}
              * ${getCommitDescriptionInNotes(commit1message)}
        """.trimIndent())
    }

    private fun prepareAndRunTagPatternTest(
        gradleFileContent: String,
        expectedResult: String,
    ) {
        gradleFile.appendText(gradleFileContent)
        runBuild()

        val tag = git.tagList().call().find {
            val actual = it.name.substring("refs/tags/".length)
            actual == expectedResult
        }
        assertThat(tag).isNotNull
    }

    @Test
    fun `when release is made and no tagPattern defined then git tag is created with default pattern`() {
        val version = "1.0.0"
        val content =  """
            version = "$version"
        """.trimIndent()

        prepareAndRunTagPatternTest(
            content,
            String.format(GradleReleasePaperworkPlugin.DEFAULT_RELEASE_COMMIT_MESSAGE_PATTERN, version)
        )
    }

    @Test
    fun `when release is made and tagPattern is defined then tag is created and named using tagPattern`() {
        val version = "1.0.0"
        val pattern = "v%s"
        val content = """
            version = "$version"
            
            releasePaperwork {
                tagPattern.set("$pattern")
            }
        """.trimIndent()

        prepareAndRunTagPatternTest(
            content,
            String.format(pattern, version)
        )
    }

    @Test
    fun `when flutter semver with build version is used then the both version and build number are incremented`() {
        val pubspecFile = File(rootProjectDir, "pubspec.yaml").also {
            it.writeText("""
                name: some-name
                version: 1.0.0+1
                environment:
                  sdk: 3.5.1
                  flutter: 3.24.1
            """.trimIndent())
        }
        gradleFile.appendText("""
            releasePaperwork {
                 projectVersionFile.set("pubspec.yaml")
                 projectVersionRegex.set("version:\\s*([^\\s]+)")
             }
        """.trimIndent())
        runBuild()

        makeCommit("some-feature")

        runBuild()

        assertThat(pubspecFile.readText()).isEqualTo("""
            name: some-name
            version: 1.1.0+2
            environment:
              sdk: 3.5.1
              flutter: 3.24.1
        """.trimIndent())
    }

    @Test
    fun `when flutter semver with pre-release version is used then the both version and build number are incremented`() {
        val pubspecFile = File(rootProjectDir, "pubspec.yaml").also {
            it.writeText("""
                name: some-name
                version: 1.0.2-4
                environment:
                  sdk: 3.5.1
                  flutter: 3.24.1
            """.trimIndent())
        }
        gradleFile.appendText("""
            releasePaperwork {
                 projectVersionFile.set("pubspec.yaml")
                 projectVersionRegex.set("version:\\s*([^\\s]+)")
             }
        """.trimIndent())
        runBuild()

        makeCommit("some-feature")

        runBuild()

        assertThat(pubspecFile.readText()).isEqualTo("""
            name: some-name
            version: 1.1.0-5
            environment:
              sdk: 3.5.1
              flutter: 3.24.1
        """.trimIndent())
    }
}
