@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)

package space.kscience.krig.build.architecture

import java.io.File
import java.util.concurrent.Callable
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.tasks.SourceSetContainer
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinProjectExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet
import org.jetbrains.kotlin.gradle.tasks.KotlinCompileTool

@Suppress("unused") // Loaded by the generated Gradle plugin descriptor.
class ArchitecturePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        require(project == project.rootProject) { "krig-architecture must be applied to the root project" }
        project.pluginManager.apply("base")

        val checkArchitecture = project.tasks.register("checkArchitecture", CheckArchitectureTask::class.java) {
            group = "verification"
            description = "Checks the declared module graph and production-package ownership baseline."
            policyDirectory.set(project.layout.projectDirectory.dir("config/architecture"))
            repositoryDirectory.set(project.layout.projectDirectory)
            reportDirectory.set(project.layout.buildDirectory.dir("reports/architecture"))
        }
        project.tasks.named("check").configure { dependsOn(checkArchitecture) }

        project.gradle.projectsEvaluated {
            val modules = project.subprojects.sortedBy { it.path }
            val projectsByPath = modules.associate { it.path to projectId(it) }
            val moduleRootMappings = modules.associate { module ->
                project.projectDir.toPath()
                    .relativize(module.projectDir.toPath())
                    .toString()
                    .replace(File.separatorChar, '/') to projectId(module)
            }
            val kotlinModels = modules.mapNotNull { module ->
                module.extensions.findByType(KotlinProjectExtension::class.java)?.let { kotlin ->
                    KotlinModuleModel(module, projectId(module), kotlin)
                }
            }
            val lazySources = kotlinModels.map { model ->
                KotlinModuleSources(
                    moduleName = model.id,
                    files = model.project.files(Callable {
                        buildList {
                            addAll(
                                productionSourceSets(model.kotlin).map { sourceSet -> sourceSet.allKotlinSources },
                            )
                            addAll(productionMetadataCompilations(model.kotlin).map { compilation ->
                                val compileTask = compilation.compileTaskProvider.get()
                                val compileTool = compileTask as? KotlinCompileTool ?: error(
                                    "Production Kotlin metadata task '${compileTask.path}' does not implement " +
                                        "KotlinCompileTool; architecture source discovery cannot fail open",
                                )
                                compileTool.sources
                            })
                        }
                    }),
                )
            }
            val lazyJavaSources = kotlinModels.map { model ->
                model.project.files(Callable {
                    buildList {
                        val javaSourceSets = model.project.extensions.findByType(SourceSetContainer::class.java)
                        productionSourceSets(model.kotlin).forEach { kotlinSourceSet ->
                            javaSourceSets?.findByName(kotlinSourceSet.name)?.let { javaSourceSet ->
                                add(javaSourceSet.allJava)
                            }
                        }
                        add(model.project.fileTree(model.project.projectDir.resolve("src")).apply {
                            include("main/java/**/*.java", "*Main/java/**/*.java")
                        })
                    }
                })
            }

            checkArchitecture.configure {
                moduleNames.set(modules.map(::projectId))
                kotlinModuleNames.set(kotlinModels.map(KotlinModuleModel::id).sorted())
                moduleRoots.set(moduleRootMappings.toSortedMap())
                productionJavaFiles.from(lazyJavaSources)
                productionKotlinSources.set(lazySources)
                sourceRoots.set(project.providers.provider {
                    collectSourceRoots(project.projectDir, kotlinModels)
                })
                javaSourceRoots.set(project.providers.provider {
                    collectJavaSourceRoots(project.projectDir, kotlinModels)
                })
                declaredEdges.set(project.providers.provider {
                    collectEdges(kotlinModels, projectsByPath)
                })
            }
        }
    }

    private fun collectSourceRoots(
        repositoryRoot: File,
        kotlinModels: List<KotlinModuleModel>,
    ): Map<String, String> {
        val result = linkedMapOf<String, String>()
        kotlinModels.forEach { model ->
            productionSourceSets(model.kotlin).forEach { sourceSet ->
                (sourceSet.kotlin.srcDirs + sourceSet.generatedKotlin.srcDirs).forEach { directory ->
                    val relativePath = repositoryRoot.toPath()
                        .relativize(directory.toPath())
                        .toString()
                        .replace(File.separatorChar, '/')
                    val previous = result.put(relativePath, model.id)
                    require(previous == null || previous == model.id) {
                        "Kotlin source root '$relativePath' belongs to both '$previous' and '${model.id}'"
                    }
                }
            }
        }
        return result.toSortedMap()
    }

    private fun collectJavaSourceRoots(
        repositoryRoot: File,
        kotlinModels: List<KotlinModuleModel>,
    ): Map<String, String> {
        val result = linkedMapOf<String, String>()
        kotlinModels.forEach { model ->
            val javaSourceSets = model.project.extensions.findByType(SourceSetContainer::class.java)
            productionSourceSets(model.kotlin).forEach { kotlinSourceSet ->
                javaSourceSets?.findByName(kotlinSourceSet.name)?.allJava?.srcDirs.orEmpty().forEach { directory ->
                    val relativePath = repositoryRoot.toPath()
                        .relativize(directory.toPath())
                        .toString()
                        .replace(File.separatorChar, '/')
                    val previous = result.put(relativePath, model.id)
                    require(previous == null || previous == model.id) {
                        "Java source root '$relativePath' belongs to both '$previous' and '${model.id}'"
                    }
                }
            }
        }
        return result.toSortedMap()
    }

    private fun collectEdges(
        kotlinModels: List<KotlinModuleModel>,
        projectsByPath: Map<String, String>,
    ): List<String> {
        val result = linkedSetOf<String>()
        kotlinModels.forEach { model ->
            val configurationNames = productionSourceSets(model.kotlin).flatMapTo(linkedSetOf()) { sourceSet ->
                listOf(
                    sourceSet.apiConfigurationName,
                    sourceSet.implementationConfigurationName,
                    sourceSet.compileOnlyConfigurationName,
                    sourceSet.runtimeOnlyConfigurationName,
                )
            }
            configurationNames.sorted().forEach configurationLoop@{ configurationName ->
                val configuration = model.project.configurations.findByName(configurationName)
                    ?: return@configurationLoop
                configuration.dependencies.withType(ProjectDependency::class.java).forEach { dependency ->
                    val dependencyName = projectsByPath[dependency.path]
                        ?: dependency.path.removePrefix(":")
                    result += "${model.id}\t$dependencyName"
                }
            }
        }
        return result.sorted()
    }

    private fun platformMainCompilations(kotlin: KotlinProjectExtension): List<KotlinCompilation<*>> {
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

    private fun productionMetadataCompilations(kotlin: KotlinProjectExtension): List<KotlinCompilation<*>> {
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

    private fun productionSourceSets(kotlin: KotlinProjectExtension): List<KotlinSourceSet> =
        platformMainCompilations(kotlin)
            .flatMapTo(linkedSetOf<KotlinSourceSet>()) { compilation -> compilation.allKotlinSourceSets }
            .sortedBy { it.name }

    private fun projectId(project: Project): String = project.path.removePrefix(":")

    private data class KotlinModuleModel(
        val project: Project,
        val id: String,
        val kotlin: KotlinProjectExtension,
    )
}
