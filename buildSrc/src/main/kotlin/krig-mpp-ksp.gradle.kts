import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/**
 * Applies the krig KSP processor to a multiplatform module.
 *
 * Common/metadata KSP emits common-safe generated API, while the JVM pass emits
 * DataForge aggregation glue. Both use a group+artifact-derived namespace so two
 * modules that happen to share a Gradle project name (e.g. `:utils`) never
 * collide at runtime.
 */

plugins {
    id("com.google.devtools.ksp")
}

plugins.withId("org.jetbrains.kotlin.multiplatform") {
    dependencies {
        add("kspCommonMainMetadata", project(":krig-ksp-processor"))
        add("kspJvm", project(":krig-ksp-processor"))
    }

    extensions.configure<KotlinMultiplatformExtension>("kotlin") {
        sourceSets.named("commonMain") {
            kotlin.srcDir(layout.buildDirectory.dir("generated/ksp/metadata/commonMain/kotlin"))
        }
    }
}

ksp {
    val groupSlug = project.group.toString()
        .ifBlank { project.rootProject.name }
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), "_")
        .trim('_')
        .ifBlank { "anon" }
    val nameSlug = project.name
        .removePrefix("krig-")
        .split('-', '_')
        .joinToString("") { s -> s.replaceFirstChar { it.uppercase() } }
        .replaceFirstChar { it.lowercase() }
        .ifBlank { "module" }
    arg("krig.generated.module", "${groupSlug}_$nameSlug")
    arg("krig.generated.layer", "auto")
}
