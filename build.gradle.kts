
plugins {
    kotlin("jvm") version "1.7.20"
    id("com.gradle.plugin-publish") version "1.0.0"
    id("tech.harmonysoft.oss.gradle.release.paperwork") version "1.0.0"
}

group = "tech.harmonysoft"
version = "1.2.0"

gradlePlugin {
    plugins {
        create("releasePaperWorkPlugin") {
            id = "tech.harmonysoft.oss.gradle.release.paperwork"
            implementationClass = "tech.harmonysoft.oss.gradle.release.paperwork.GradleReleasePaperworkPlugin"
            displayName = "gradle git release paperwork"
            description = "Automatically populates release notes by git commits on release"
        }
    }
}

pluginBundle {
    website = "http://gradle-release-paperwork.oss.harmonysoft.tech/"
    vcsUrl = "https://github.com/denis-zhdanov/gradle-release-paperwork"
    tags = listOf("git", "release")
}

repositories {
    mavenCentral()
}

dependencies {
    api(gradleApi())
    api("org.eclipse.jgit:org.eclipse.jgit:6.3.0.202209071007-r")

    testImplementation(gradleTestKit())
    testImplementation("org.junit.jupiter:junit-jupiter:5.9.1")
    testImplementation("org.assertj:assertj-core:3.23.1")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
