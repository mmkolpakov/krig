@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)

package space.kscience.krig.build.architecture

import java.io.File
import java.util.concurrent.Callable
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.DocsType
import org.gradle.api.tasks.SourceSetContainer
import org.jetbrains.kotlin.gradle.dsl.KotlinProjectExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinCompileTool

@Suppress("unused") // Loaded by the generated Gradle plugin descriptor.
class ArchitecturePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        require(project == project.rootProject) { "krig-architecture must be applied to the root project" }
        project.pluginManager.apply("base")

        val architecturePolicyDirectory = project.layout.projectDirectory.dir("config/architecture")
        val libraryModules = ArchitecturePolicyLoader.loadModules(architecturePolicyDirectory.file("modules.tsv").asFile)
            .values
            .filter { module -> module.kind == ModuleKind.Library }
            .map(ModulePolicy::name)
            .sorted()
        val fragmentDependencyScope = project.configurations.dependencyScope("krigArchitectureFragmentDependencies")
        fragmentDependencyScope.configure {
            libraryModules.forEach { module ->
                dependencies.add(project.dependencies.project(":$module"))
            }
        }
        val architectureFragments = project.configurations.resolvable("krigArchitectureFragments") {
            extendsFrom(fragmentDependencyScope.get())
            isTransitive = false
            attributes {
                attribute(
                    Category.CATEGORY_ATTRIBUTE,
                    project.objects.named(Category::class.java, Category.DOCUMENTATION),
                )
                attribute(
                    DocsType.DOCS_TYPE_ATTRIBUTE,
                    project.objects.named(DocsType::class.java, ARCHITECTURE_FRAGMENT_FORMAT),
                )
            }
        }
        val checkArchitecture = project.tasks.register("checkArchitecture", CheckArchitectureTask::class.java) {
            group = "verification"
            description = "Checks the declared module graph and production-package ownership baseline."
            policyDirectory.set(architecturePolicyDirectory)
            dependencyFragments.from(architectureFragments)
            repositoryDirectory.set(project.layout.projectDirectory)
            reportDirectory.set(project.layout.buildDirectory.dir("reports/architecture"))
        }
        project.tasks.named("check").configure { dependsOn(checkArchitecture) }

        project.gradle.projectsEvaluated {
            val modules = project.subprojects.sortedBy { it.path }
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

    private fun projectId(project: Project): String = project.path.removePrefix(":")

    private data class KotlinModuleModel(
        val project: Project,
        val id: String,
        val kotlin: KotlinProjectExtension,
    )
}
