package tech.harmonysoft.oss.gradle.release.paperwork

import org.gradle.api.provider.Property

interface GradleReleasePaperworkPluginExtension {

    val releaseNotesFile: Property<String>

    val projectVersionFile: Property<String>

    val projectVersionRegex: Property<String>

    val additionalReleaseDescription: Property<() -> String>

    val changeDescription: Property<(String) -> String?>

    val maxChangesPerRelease: Property<Int>

    val tagPrefix: Property<String>
}