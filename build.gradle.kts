plugins {
    kotlin("jvm") version "1.7.20"
    `java-gradle-plugin`
}

gradlePlugin {
    plugins {
        create("releasePaperWorkPlugin") {
            id = "tech.harmonysoft.oss.gradle.release.paperwork"
            implementationClass = "tech.harmonysoft.oss.gradle.release.paperwork.GradleReleasePaperworkPlugin"
        }
    }
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
