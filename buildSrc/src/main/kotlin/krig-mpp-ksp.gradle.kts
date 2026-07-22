@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)

import org.gradle.jvm.tasks.Jar
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet
import org.jetbrains.kotlin.gradle.tasks.KotlinCompileTool
import space.kscience.krig.build.generatedNamespace

/**
 * Applies the krig KSP processor to a multiplatform module.
 *
 * Common metadata and every leaf target run the processor independently. The processor
 * infers a common-safe layer for non-JVM targets and a complete layer for JVM. Generated
 * packages use a group+artifact-derived namespace so same-named projects do not collide.
 */

plugins {
    id("com.google.devtools.ksp")
}

plugins.withId("org.jetbrains.kotlin.multiplatform") {
    val kotlin = extensions.getByType<KotlinMultiplatformExtension>()
    val commonMetadataSourceArchives = tasks.withType<Jar>().matching { task ->
        task.name == "sourcesJar" || task.name == "metadataSourcesJar"
    }
    dependencies.add("kspCommonMainMetadata", project(":krig-ksp-processor"))
    kotlin.targets.configureEach {
        if (platformType == KotlinPlatformType.common) {
            compilations.configureEach {
                val compilation = this
                if (
                    compilation.name != KotlinCompilation.MAIN_COMPILATION_NAME &&
                    compilation.defaultSourceSet.name == KotlinSourceSet.COMMON_MAIN_SOURCE_SET_NAME
                ) {
                    val compileTask = compilation.compileTaskProvider.get()
                    val compileTool = compileTask as? KotlinCompileTool ?: error(
                        "Kotlin metadata task '${compileTask.path}' does not implement KotlinCompileTool",
                    )
                    val declaredSourceRoots = compilation.allKotlinSourceSets
                        .flatMap { sourceSet -> sourceSet.kotlin.srcDirs + sourceSet.generatedKotlin.srcDirs }
                        .map { sourceRoot -> sourceRoot.toPath().toAbsolutePath().normalize() }
                    val generatedSources = compileTool.sources.asFileTree.matching {
                        exclude { element ->
                            val sourcePath = element.file.toPath().toAbsolutePath().normalize()
                            declaredSourceRoots.any(sourcePath::startsWith)
                        }
                    }
                    commonMetadataSourceArchives.configureEach {
                        from(generatedSources) {
                            into(KotlinSourceSet.COMMON_MAIN_SOURCE_SET_NAME)
                        }
                    }
                }
            }
            return@configureEach
        }
        require(platformType != KotlinPlatformType.androidJvm) {
            "krig-mpp-ksp does not yet support Android variant-specific KSP configurations"
        }
        requireNotNull(compilations.findByName(KotlinCompilation.MAIN_COMPILATION_NAME)) {
            "Kotlin target '$name' has no '${KotlinCompilation.MAIN_COMPILATION_NAME}' compilation"
        }
        val configurationTargetName = when (name) {
            "jsLegacy", "jsIr" -> "js"
            else -> name
        }
        val configurationName = "ksp${configurationTargetName.replaceFirstChar { it.uppercase() }}"
        dependencies.add(configurationName, project(":krig-ksp-processor"))
    }
}

ksp {
    val projectName = project.name
    val projectPath = project.path
    val namespace = project.provider {
        generatedNamespace(
            group = project.group.toString(),
            projectName = projectName,
            defaultGroup = projectPath.replace(':', '.'),
        ).value
    }
    arg("krig.generated.module", namespace)
    arg("krig.generated.layer", "auto")
}
