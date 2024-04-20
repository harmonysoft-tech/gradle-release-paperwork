plugins {
    `java-gradle-plugin`
    id("com.gradle.plugin-publish") version "1.2.1"
    kotlin("jvm") version "1.9.23"
    id("tech.harmonysoft.oss.gradle.release.paperwork") version "1.8.0"
}

group = "tech.harmonysoft"
version = "1.7.0"

gradlePlugin {
    website = "http://gradle-release-paperwork.oss.harmonysoft.tech/"
    vcsUrl = "https://github.com/denis-zhdanov/gradle-release-paperwork"
    plugins {
        create("releasePaperworkPlugin") {
            id = "tech.harmonysoft.oss.gradle.release.paperwork"
            implementationClass = "tech.harmonysoft.oss.gradle.release.paperwork.GradleReleasePaperworkPlugin"
            displayName = "gradle git release paperwork"
            description = "handles release paperwork activities - release notes population, git tag creation, etc"
            tags = listOf("git", "release")
        }
    }
}

repositories {
    mavenCentral()
}

dependencies {
    api("org.eclipse.jgit:org.eclipse.jgit:6.9.0.202403050737-r")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("org.assertj:assertj-core:3.25.3")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
