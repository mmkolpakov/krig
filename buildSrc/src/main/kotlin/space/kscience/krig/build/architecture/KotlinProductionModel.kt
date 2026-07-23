@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)

package space.kscience.krig.build.architecture

import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinProjectExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet

internal fun platformMainCompilations(kotlin: KotlinProjectExtension): List<KotlinCompilation<*>> {
    val compilations = when (kotlin) {
        is KotlinMultiplatformExtension -> kotlin.targets
            .filter { target -> target.platformType != KotlinPlatformType.common }
            .mapTo(linkedSetOf()) { target ->
                target.compilations.findByName(KotlinCompilation.MAIN_COMPILATION_NAME)
                    ?: error(
                        "Kotlin target '${target.name}' has no '${KotlinCompilation.MAIN_COMPILATION_NAME}' " +
                            "compilation; production variant discovery is not supported safely",
                    )
            }

        is KotlinJvmProjectExtension -> listOf(
            kotlin.target.compilations.findByName(KotlinCompilation.MAIN_COMPILATION_NAME)
                ?: error("Kotlin JVM target has no '${KotlinCompilation.MAIN_COMPILATION_NAME}' compilation"),
        )

        else -> error(
            "Unsupported Kotlin project model '${kotlin::class.qualifiedName}'; " +
                "production compilations cannot be determined safely",
        )
    }
    return compilations.sortedBy { it.compileKotlinTaskName }
}

internal fun productionMetadataCompilations(kotlin: KotlinProjectExtension): List<KotlinCompilation<*>> {
    if (kotlin !is KotlinMultiplatformExtension) return emptyList()
    val productionSourceSets = productionSourceSets(kotlin).toSet()
    return kotlin.targets
        .filter { target -> target.platformType == KotlinPlatformType.common }
        .flatMap { target -> target.compilations }
        .filter { compilation ->
            compilation.name != KotlinCompilation.MAIN_COMPILATION_NAME &&
                compilation.defaultSourceSet.name == KotlinSourceSet.COMMON_MAIN_SOURCE_SET_NAME &&
                compilation.defaultSourceSet in productionSourceSets
        }
        .distinct()
        .sortedBy { it.compileKotlinTaskName }
}

internal fun productionSourceSets(kotlin: KotlinProjectExtension): List<KotlinSourceSet> =
    platformMainCompilations(kotlin)
        .flatMapTo(linkedSetOf<KotlinSourceSet>()) { compilation -> compilation.allKotlinSourceSets }
        .sortedBy { it.name }
