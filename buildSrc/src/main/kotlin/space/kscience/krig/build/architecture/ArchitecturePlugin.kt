@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)

package space.kscience.krig.build.architecture

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import org.jetbrains.kotlin.gradle.dsl.KotlinProjectExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet

@Suppress("unused") // Loaded by the generated Gradle plugin descriptor.
class ArchitecturePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        require(project == project.rootProject) { "krig-architecture must be applied to the root project" }
        project.pluginManager.apply("base")

        val checkArchitecture = project.tasks.register("checkArchitecture", CheckArchitectureTask::class.java) {
            group = "verification"
            description = "Checks the declared module graph and split-package baseline."
            policyDirectory.set(project.layout.projectDirectory.dir("config/architecture"))
            repositoryDirectory.set(project.layout.projectDirectory)
            reportDirectory.set(project.layout.buildDirectory.dir("reports/architecture"))
        }
        project.tasks.named("check").configure { dependsOn(checkArchitecture) }

        project.gradle.projectsEvaluated {
            val modules = project.subprojects.sortedBy { it.path }
            val projectsByPath = modules.associate { it.path to projectId(it) }
            val collectedEdges = linkedSetOf<String>()
            val collectedSourceRoots = linkedMapOf<String, String>()
            val collectedKotlinModules = linkedSetOf<String>()
            val collectedModuleRoots = linkedMapOf<String, String>()

            checkArchitecture.configure {
                moduleNames.set(modules.map(::projectId))
                modules.forEach moduleLoop@{ module ->
                    val moduleId = projectId(module)
                    val modulePath = project.projectDir.toPath()
                        .relativize(module.projectDir.toPath())
                        .toString()
                        .replace(java.io.File.separatorChar, '/')
                    collectedModuleRoots[modulePath] = moduleId

                    module.extensions.findByType(SourceSetContainer::class.java)
                        ?.findByName(SourceSet.MAIN_SOURCE_SET_NAME)
                        ?.let { sourceSet -> productionJavaFiles.from(sourceSet.allJava) }
                    val conventionalJava = module.fileTree(module.projectDir.resolve("src"))
                    conventionalJava.include("main/java/**/*.java", "*Main/java/**/*.java")
                    productionJavaFiles.from(conventionalJava)

                    val kotlin = module.extensions.findByType(KotlinProjectExtension::class.java)
                        ?: return@moduleLoop
                    collectedKotlinModules += moduleId
                    val mainSourceSets = kotlin.sourceSets
                        .filter(::isMainSourceSet)
                        .sortedBy { it.name }

                    if (module.pluginManager.hasPlugin("com.google.devtools.ksp")) {
                        dependsOn(
                            module.tasks.matching { task ->
                                task.name == "kspCommonMainKotlinMetadata" || task.name == "kspKotlinJvm"
                            },
                        )
                    }

                    mainSourceSets.forEach { sourceSet ->
                        sourceFiles.from(sourceSet.allKotlinSources)
                        (sourceSet.kotlin.srcDirs + sourceSet.generatedKotlin.srcDirs).forEach { directory ->
                            val relativePath = project.projectDir.toPath()
                                .relativize(directory.toPath())
                                .toString()
                                .replace(java.io.File.separatorChar, '/')
                            val previous = collectedSourceRoots.put(relativePath, moduleId)
                            require(previous == null || previous == moduleId) {
                                "Kotlin source root '$relativePath' belongs to both '$previous' and '$moduleId'"
                            }
                        }
                    }

                    val configurationNames = mainSourceSets.flatMapTo(linkedSetOf()) { sourceSet ->
                        listOf(
                            sourceSet.apiConfigurationName,
                            sourceSet.implementationConfigurationName,
                            sourceSet.compileOnlyConfigurationName,
                            sourceSet.runtimeOnlyConfigurationName,
                        )
                    }
                    configurationNames.sorted().forEach configurationLoop@{ configurationName ->
                        val configuration = module.configurations.findByName(configurationName)
                            ?: return@configurationLoop
                        configuration.dependencies.withType(ProjectDependency::class.java).forEach { dependency ->
                            val dependencyName = projectsByPath[dependency.path]
                                ?: dependency.path.removePrefix(":")
                            collectedEdges += "$moduleId\t$dependencyName"
                        }
                    }
                }
                declaredEdges.set(collectedEdges.sorted())
                kotlinModuleNames.set(collectedKotlinModules.sorted())
                moduleRoots.set(collectedModuleRoots.toSortedMap())
                sourceRoots.set(collectedSourceRoots.toSortedMap())
            }
        }
    }

    private fun isMainSourceSet(sourceSet: KotlinSourceSet): Boolean =
        sourceSet.name == "main" || sourceSet.name.endsWith("Main")

    private fun projectId(project: Project): String = project.path.removePrefix(":")
}
