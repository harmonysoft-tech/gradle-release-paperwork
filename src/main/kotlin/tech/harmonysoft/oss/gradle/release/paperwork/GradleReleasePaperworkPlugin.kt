package tech.harmonysoft.oss.gradle.release.paperwork

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.revwalk.RevCommit
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

class GradleReleasePaperworkPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        if (project.parent != null) {
            // we want to apply plugin only to the root project
            return
        }
        val extension = project.extensions.create("releasePaperwork", GradleReleasePaperworkPluginExtension::class.java)
        project.task("release-paperwork") { task ->
            task.doLast {
                val releaseNotesFile = getReleaseNotesFile(project, extension)
                val lastReleasedInfo = maybeGetLastReleasedInfo(project, releaseNotesFile)
                val changes = getUnreleasedChanges(project, extension, lastReleasedInfo)
                if (changes.isEmpty()) {
                    project.logger.lifecycle("No changes to release are detected")
                    return@doLast
                }

                val currentProjectVersion = parseCurrentVersion(project, extension)
                val versionToRelease = if (currentProjectVersion != lastReleasedInfo?.version) {
                    lastReleasedInfo?.version?.let {
                        project.logger.lifecycle(
                            "Current project version ($currentProjectVersion) differs from the last released "
                            + "version ($it), assuming that version $currentProjectVersion is set manually, "
                            + "using it for releasing"
                        )
                    } ?: project.logger.lifecycle(
                        "No previously released version is found"
                    )

                    currentProjectVersion
                } else {
                    incrementVersion(currentProjectVersion)
                }
                project.logger.lifecycle("Using version '$versionToRelease' for releasing")
                populateReleaseNotes(project, extension, releaseNotesFile, versionToRelease, changes)
                applyNewVersion(project, extension, currentProjectVersion, versionToRelease)
                val commit = commitChanges(
                    project = project,
                    newVersion = versionToRelease,
                    changedFiles = arrayOf(releaseNotesFile, getProjectVersionFile(project, extension))
                )
                createTag(versionToRelease, project, commit.id.name)
            }
        }
    }

    private fun parseCurrentVersion(project: Project, extension: GradleReleasePaperworkPluginExtension): String {
        val projectVersionFile = getProjectVersionFile(project, extension)
        val versionRegex = getProjectVersionRegex(extension)
        val lines = projectVersionFile.readLines()
        for (line in lines) {
            versionRegex.find(line)?.let {
                if (it.groups.size != 2) {
                    throw GradleException(
                        "bad project version regex ($versionRegex), it's expected to have a single capturing group "
                        + "but has ${it.groups.size - 1}"
                    )
                }
                return it.groupValues[1]
            }
        }
        throw GradleException(
            "can't extract project version from file ${projectVersionFile.canonicalPath} using "
            + "the following regex: $versionRegex"
        )
    }

    private fun getProjectVersionRegex(extension: GradleReleasePaperworkPluginExtension): Regex {
        return if (extension.projectVersionRegex.isPresent) {
            extension.projectVersionRegex.get().toRegex()
        } else {
            DEFAULT_PROJECT_VERSION_REGEX
        }
    }

    private fun getProjectVersionFile(project: Project, extension: GradleReleasePaperworkPluginExtension): File {
        return if (extension.projectVersionFile.isPresent) {
            project.file(extension.projectVersionFile.get()).apply {
                if (!isFile) {
                    throw GradleException("project version file doesn't exist (${extension.projectVersionFile.get()})")
                }
            }
        } else {
            val groovyFile = project.file("build.gradle")
            if (groovyFile.isFile) {
                groovyFile
            } else {
                val kotlinFile = project.file("build.gradle.kts")
                if (!kotlinFile.isFile) {
                    throw GradleException(
                        "can't extract project version - neither ${groovyFile.name} nor ${kotlinFile.name} are found"
                    )
                }
                kotlinFile
            }
        }
    }

    private fun getReleaseNotesFile(project: Project, extension: GradleReleasePaperworkPluginExtension): File {
        val releaseNotesFilePath = if (extension.releaseNotesFile.isPresent) {
            extension.releaseNotesFile.get()
        } else {
            DEFAULT_RELEASE_NOTES_FILE
        }
        val releaseNotesFile = project.file(releaseNotesFilePath)
        project.logger.lifecycle("Using release notes file ${releaseNotesFile.canonicalPath}")
        return releaseNotesFile
    }

    private fun maybeGetLastReleasedInfo(project: Project, releaseNotesFile: File): ReleaseInfo? {
        if (!releaseNotesFile.isFile) {
            return null
        }
        releaseNotesFile.useLines { lines ->
            var releaseVersion = ""
            for (line in lines) {
                if (line.isBlank()) {
                    continue
                }
                if (releaseVersion.isBlank()) {
                    releaseVersion = extractReleaseVersion(line)
                    continue
                }
                val lastCommitHash = extractCommitHash(line)
                return ReleaseInfo(releaseVersion, lastCommitHash)
            }
        }
        project.logger.lifecycle(
            "No information about the last released version is extracted from ${releaseNotesFile.canonicalPath}"
        )
        return null
    }

    private fun extractReleaseVersion(releaseDescriptionFromNotesFile: String): String {
        if (releaseDescriptionFromNotesFile.isBlank()) {
            throw GradleException("can't extract released version name from blank line")
        }
        val prefix = "## v"
        if (!releaseDescriptionFromNotesFile.startsWith(prefix)) {
            throw GradleException(
                "can't extract released version name from line '$releaseDescriptionFromNotesFile' - it's expected "
                + "to have format '$RELEASE_DESCRIPTION_FORMAT' but doesn't start from prefix '$prefix'"
            )
        }
        val i = releaseDescriptionFromNotesFile.indexOf(" ", prefix.length)
        if (i < 0) {
            throw GradleException(
                "can't extract released version name from line '$releaseDescriptionFromNotesFile' - it's expected "
                + "to have format '$RELEASE_DESCRIPTION_FORMAT' but doesn't have a white space"
            )
        }
        if (i < 2) {
            throw GradleException(
                "can't extract released version name from line '$releaseDescriptionFromNotesFile' - it's expected "
                + "to have format '$RELEASE_DESCRIPTION_FORMAT' but doesn't have any symbols between 'v' "
                + "and white space"
            )
        }
        return releaseDescriptionFromNotesFile.substring(prefix.length, i)
    }

    private fun extractCommitHash(commitDescriptionLineFromNotesFiles: String): String {
        return COMMIT_DESCRIPTION_SUFFIX_REGEX.find(commitDescriptionLineFromNotesFiles)?.let { match ->
            val commitHashStartIndex = match.range.last + 1
            if (commitHashStartIndex >= commitDescriptionLineFromNotesFiles.length) {
                throw GradleException(
                    "can't extract commit hash from line '$commitDescriptionLineFromNotesFiles' - it's expected "
                    + "to have format  '$COMMIT_DESCRIPTION_FORMAT' and it starts from text matching regex "
                    + "'$COMMIT_DESCRIPTION_SUFFIX_REGEX' but there is no text after it"
                )
            }
            val i = commitDescriptionLineFromNotesFiles.indexOf(' ', commitHashStartIndex)
            if (i <= 0) {
                throw GradleException(
                    "can't extract commit hash from line '$commitDescriptionLineFromNotesFiles' - it's expected "
                    + "to have format  '$COMMIT_DESCRIPTION_FORMAT' and it starts from text matching regex "
                    + "'$COMMIT_DESCRIPTION_SUFFIX_REGEX' but there is no white space in the reminder line "
                    + "('${commitDescriptionLineFromNotesFiles.substring(commitHashStartIndex)}')"
                )
            }
            commitDescriptionLineFromNotesFiles.substring(commitHashStartIndex, i)
        } ?: throw GradleException(
            "can't extract commit hash from line '$commitDescriptionLineFromNotesFiles' - it's expected "
            + "to have format  '$COMMIT_DESCRIPTION_FORMAT' but doesn't start from text matching regex "
            + "'$COMMIT_DESCRIPTION_SUFFIX_REGEX'"
        )
    }

    private fun incrementVersion(version: String): String {
        val minorTextStart = version.indexOf(".") + 1
        if (minorTextStart <= 0 || minorTextStart >= version.length) {
            throw GradleException(
                "can't increment project version from current version '$version' - it's expected to conform "
                + "to semver format but there is no point symbol (.) in it"
            )
        }

        val minorTextEnd = version.indexOf(".", minorTextStart)
        if (minorTextEnd <= minorTextStart) {
            throw GradleException(
                "can't increment project version from current version '$version' - it's expected to conform "
                + "to semver format but there is no second point symbol (.) in it"
            )
        }

        val minorVersionString = version.substring(minorTextStart, minorTextEnd)
        try {
            val currentMinor = minorVersionString.toInt()
            return version.substring(0, minorTextStart) + (currentMinor + 1) + ".0"
        } catch (e: Exception) {
            throw GradleException(
                "can't increment project version from current version '$version' - it's expected to conform "
                + "to semver format but its minor version ($minorVersionString) is not a number"
            )
        }
    }

    private fun getUnreleasedChanges(
        project: Project,
        extension: GradleReleasePaperworkPluginExtension,
        lastReleaseInfo: ReleaseInfo?
    ): List<String> {
        val log = Git.open(project.rootDir).log().call()
        val result = mutableListOf<String>()
        val maxChanges = getMaxChangesPerRelease(extension)
        for (commit in log) {
            if (RELEASE_COMMIT_REGEX.matches(commit.shortMessage)) {
                // skip release commit
                continue
            }
            if (commit.id.name == lastReleaseInfo?.lastReleasedCommitHash) {
                break
            }
            val commitMessage = commit.shortMessage.trim().replace("\n", " ")
            val changeDescription = if (extension.changeDescription.isPresent) {
                extension.changeDescription.get()(commitMessage).apply {
                    if (this.isNullOrBlank()) {
                        project.logger.lifecycle(
                            "Commit ${commit.id.name()} is skipped because its message is dropped by custom "
                            + "commit description filtering logic ($commitMessage)"
                        )
                    }
                }
            } else {
                commitMessage
            }
            if (!changeDescription.isNullOrBlank()) {
                result += "  * ${commit.id.name} $changeDescription"
                if (result.size > maxChanges) {
                    break
                }
            }
        }
        return result
    }

    private fun getMaxChangesPerRelease(extension: GradleReleasePaperworkPluginExtension): Int {
        return if (extension.maxChangesPerRelease.isPresent) {
            extension.maxChangesPerRelease.get().takeIf { it > 0 } ?: Int.MAX_VALUE
        } else {
            DEFAULT_MAX_CHANGES_PER_RELEASE
        }
    }

    private fun populateReleaseNotes(
        project: Project,
        extension: GradleReleasePaperworkPluginExtension,
        releaseNotesFile: File,
        newVersion: String,
        changes: List<String>
    ) {
        val tmpFile = Files.createTempFile("gradle-release-notes", "").toFile()

        val additionalReleaseDescription = if (extension.additionalReleaseDescription.isPresent) {
            extension.additionalReleaseDescription.get()()
        } else {
            null
        }
        tmpFile.appendText(getReleaseDescription(newVersion, additionalReleaseDescription))
        tmpFile.appendText("\n")

        val maxChanges = getMaxChangesPerRelease(extension)
        changes.forEachIndexed { i, change ->
            if (i < maxChanges) {
                project.logger.lifecycle("Adding the following change into release notes: $change")
                tmpFile.appendText(change)
                tmpFile.appendText("\n")
            } else if (i == maxChanges) {
                tmpFile.appendText("  * ...")
            }
        }
        if (releaseNotesFile.isFile) {
            FileInputStream(releaseNotesFile).channel.use { from ->
                FileOutputStream(tmpFile, true).channel.use { to ->
                    to.transferFrom(from, Files.size(tmpFile.toPath()), Files.size(releaseNotesFile.toPath()))
                }
            }
        }
        Files.move(tmpFile.toPath(), releaseNotesFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    private fun applyNewVersion(
        project: Project,
        extension: GradleReleasePaperworkPluginExtension,
        currentVersion: String,
        newVersion: String
    ) {
        val projectVersionFile = getProjectVersionFile(project, extension)
        val projectVersionRegex = getProjectVersionRegex(extension)
        val oldContent = projectVersionFile.readText()
        val newContent = projectVersionRegex.find(oldContent)?.let { match ->
            val regionWithNewVersion = oldContent.substring(match.range).replace(currentVersion, newVersion)
            oldContent.substring(0, match.range.first) + regionWithNewVersion +
            oldContent.substring(match.range.last + 1)
        } ?: throw GradleException(
            "can't apply new version ($newVersion) to file ${projectVersionFile.canonicalPath} - can't find "
            + "version there using regex $projectVersionRegex"
        )
        projectVersionFile.writeText(newContent)
    }

    private fun commitChanges(project: Project, newVersion: String, vararg changedFiles: File): RevCommit {
        val git = Git.open(project.rootDir)
        for (file in changedFiles) {
            val pathToCommit = project.rootDir.toPath().relativize(file.toPath()).toString()
            project.logger.lifecycle("committing file $pathToCommit")
            git.add().addFilepattern(pathToCommit).call()
        }
        return git.commit().setMessage(String.format(RELEASE_COMMIT_MESSAGE_PATTERN, newVersion)).call()
    }

    private fun createTag(newVersion: String, project: Project, commitId: String) {
        Git
            .open(project.rootDir)
            .tag()
            .setName(commitId)
            .setMessage(String.format(RELEASE_COMMIT_MESSAGE_PATTERN, newVersion)).call()
    }

    companion object {
        val DEFAULT_PROJECT_VERSION_REGEX = """version\s*=\s*['"]([^'"]+)""".toRegex()
        const val DEFAULT_RELEASE_NOTES_FILE = "RELEASE_NOTES.md"
        const val RELEASE_COMMIT_MESSAGE_PATTERN = "release %s"
        val RELEASE_COMMIT_REGEX = RELEASE_COMMIT_MESSAGE_PATTERN.replace("%s", "\\S+").toRegex()
        const val RELEASE_DESCRIPTION_FORMAT = "v<version> released on <date><additional-release-description>"
        const val COMMIT_DESCRIPTION_FORMAT = "  * <commit-hash> <commit-description>"
        val COMMIT_DESCRIPTION_SUFFIX_REGEX = """\s+\*\s+""".toRegex()
        val DATE_SYSTEM_PROPERTY_NAME = "paperwork.release.date"
        val ZONE_UTC = ZoneId.of("UTC")
        private val DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("dd MMM yyyy z")
        private const val DEFAULT_MAX_CHANGES_PER_RELEASE = 20

        fun getReleaseDescription(newVersion: String, additionalDescription: String?): String {
            val dateTime = System.getProperty(DATE_SYSTEM_PROPERTY_NAME)?.let {
                // we allow to overwrite it in tests
                LocalDate.parse(it).atStartOfDay().atZone(ZONE_UTC)
            } ?: ZonedDateTime.now(ZONE_UTC)
            return getReleaseDescription(newVersion, dateTime, additionalDescription)
        }

        fun getReleaseDescription(newVersion: String, dateTime: ZonedDateTime, additionalDescription: String?): String {
            return buildString {
                append("## v")
                append(newVersion)
                append(" released on ")
                append(DATE_FORMATTER.format(dateTime))
                additionalDescription?.let {
                    append(it)
                }
            }
        }
    }

    private data class ReleaseInfo(
        val version: String,
        val lastReleasedCommitHash: String
    )
}