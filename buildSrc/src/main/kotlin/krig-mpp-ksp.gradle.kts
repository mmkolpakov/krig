@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)

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
            generatedKotlin.srcDir(layout.buildDirectory.dir("generated/ksp/metadata/commonMain/kotlin"))
        }
    }

    tasks.matching { task ->
        task.name.startsWith("compile") && task.name.contains("Kotlin")
    }.configureEach {
        dependsOn("kspCommonMainKotlinMetadata")
    }

    tasks.matching { task ->
        task.name.startsWith("ksp") && task.name != "kspCommonMainKotlinMetadata"
    }.configureEach {
        dependsOn("kspCommonMainKotlinMetadata")
    }

    tasks.matching { task ->
        task.name == "detekt"
    }.configureEach {
        dependsOn("kspCommonMainKotlinMetadata")
    }
}

ksp {
    val groupSlug = project.group.toString()
        .ifBlank { project.rootProject.name }
        .lowercase()
        .split(Regex("[^a-z0-9]+"))
        .filter(String::isNotBlank)
        .joinToString(".")
        .ifBlank { "anon" }
    val nameSlug = project.name
        .removePrefix("krig-")
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), "")
        .ifBlank { "module" }
    arg("krig.generated.module", "$groupSlug.$nameSlug")
    arg("krig.generated.layer", "auto")
}
