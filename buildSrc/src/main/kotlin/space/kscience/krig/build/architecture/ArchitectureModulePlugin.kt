@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)

package space.kscience.krig.build.architecture

import java.nio.charset.StandardCharsets
import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.DocsType
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.jetbrains.kotlin.gradle.dsl.KotlinProjectExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet

@CacheableTask
internal abstract class GenerateArchitectureFragmentTask : DefaultTask() {
    @get:Input
    abstract val moduleName: Property<String>

    @get:Input
    abstract val kotlinModelPresent: Property<Boolean>

    @get:Input
    abstract val declarations: ListProperty<String>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun generate() {
        require(kotlinModelPresent.get()) {
            "Module '${moduleName.get()}' applies krig-architecture-module without a supported Kotlin plugin"
        }
        val rows = declarations.get().sorted()
        require(rows.size == rows.toSet().size) {
            "Module '${moduleName.get()}' contains duplicate project dependency declarations"
        }
        outputFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText(
                buildString {
                    appendLine("format\t$ARCHITECTURE_FRAGMENT_FORMAT")
                    appendLine("module\t${moduleName.get()}")
                    appendLine(PROJECT_DEPENDENCY_HEADER.joinToString("\t"))
                    rows.forEach(::appendLine)
                },
                StandardCharsets.UTF_8,
            )
        }
    }
}

@Suppress("unused") // Loaded by the generated Gradle plugin descriptor.
class ArchitectureModulePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val moduleId = project.path.removePrefix(":")
        require(moduleId.isNotEmpty()) { "krig-architecture-module must not be applied to the root project" }

        val generateFragment = project.tasks.register(
            "generateArchitectureFragment",
            GenerateArchitectureFragmentTask::class.java,
        ) {
            group = "verification"
            description = "Generates the typed project-dependency declaration fragment."
            moduleName.set(moduleId)
            kotlinModelPresent.convention(false)
            declarations.convention(emptyList())
            outputFile.set(project.layout.buildDirectory.file("architecture/dependencies.tsv"))
        }
        val elements = project.configurations.consumable("krigArchitectureElements") {
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
        project.artifacts.add(elements.name, generateFragment.flatMap { task -> task.outputFile })

        var kotlinModelConfigured = false
        fun configureKotlinModel() {
            check(!kotlinModelConfigured) { "Module '$moduleId' applies more than one supported Kotlin project plugin" }
            kotlinModelConfigured = true
            val kotlin = project.extensions.getByType(KotlinProjectExtension::class.java)
            generateFragment.configure {
                kotlinModelPresent.set(true)
                declarations.set(project.providers.provider {
                    collectProjectDependencies(project, kotlin, moduleId)
                })
            }
        }
        project.pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") { configureKotlinModel() }
        project.pluginManager.withPlugin("org.jetbrains.kotlin.jvm") { configureKotlinModel() }
    }

    private fun collectProjectDependencies(
        project: Project,
        kotlin: KotlinProjectExtension,
        moduleId: String,
    ): List<String> {
        val productionConfigurationNames = platformMainCompilations(kotlin)
            .flatMap { compilation ->
                listOfNotNull(
                    compilation.compileDependencyConfigurationName,
                    compilation.runtimeDependencyConfigurationName,
                )
            }
            .distinct()
            .sorted()
        productionConfigurationNames.forEach { configurationName ->
            // Executes withDependencies/defaultDependencies without resolving the dependency graph.
            project.configurations.getByName(configurationName).incoming.dependencies
        }

        val sourceSets = productionSourceSets(kotlin)
        val classifiedConfigurations = linkedMapOf<String, Pair<String, ProjectDependencyScope>>()
        sourceSets.forEach { sourceSet ->
            sourceSet.dependencyConfigurations().forEach { (scope, configurationName) ->
                val classification = sourceSet.name to scope
                val previous = classifiedConfigurations.put(configurationName, classification)
                require(previous == null || previous == classification) {
                    "Production dependency configuration '$configurationName' maps to both " +
                        "${previous?.first}/${previous?.second?.policyName} and " +
                        "${classification.first}/${classification.second.policyName}"
                }
            }
        }
        val declarations = buildList {
            sourceSets.forEach { sourceSet ->
                sourceSet.dependencyConfigurations().forEach { (scope, configurationName) ->
                    val configuration = project.configurations.getByName(configurationName)
                    configuration.dependencies.withType(ProjectDependency::class.java)
                        .sortedBy(ProjectDependency::getPath)
                        .forEach { dependency ->
                            requirePlainProjectDependency(moduleId, sourceSet.name, scope, dependency)
                            add(
                                listOf(
                                    moduleId,
                                    dependency.path.removePrefix(":"),
                                    sourceSet.name,
                                    scope.policyName,
                                ).joinToString("\t"),
                            )
                        }
                }
            }
        }
        rejectUnclassifiedProductionProjectDependencies(
            project = project,
            moduleId = moduleId,
            productionConfigurationNames = productionConfigurationNames,
            classifiedConfigurationNames = classifiedConfigurations.keys,
        )
        return declarations.sorted()
    }

    private fun rejectUnclassifiedProductionProjectDependencies(
        project: Project,
        moduleId: String,
        productionConfigurationNames: List<String>,
        classifiedConfigurationNames: Set<String>,
    ) {
        val reachability = linkedMapOf<String, MutableSet<String>>()
        productionConfigurationNames.forEach { rootConfigurationName ->
            project.configurations.getByName(rootConfigurationName).hierarchy.forEach { configuration ->
                reachability.getOrPut(configuration.name, ::sortedSetOf) += rootConfigurationName
            }
        }
        val violations = reachability.entries
            .filter { (configurationName) -> configurationName !in classifiedConfigurationNames }
            .flatMap { (configurationName, rootConfigurationNames) ->
                project.configurations.getByName(configurationName).dependencies
                    .withType(ProjectDependency::class.java)
                    .sortedBy(ProjectDependency::getPath)
                    .map { dependency ->
                        "Unsupported project dependency '$moduleId -> " +
                            "${dependency.path.removePrefix(":")}' declared in unclassified production " +
                            "configuration '$configurationName' (reachable from " +
                            rootConfigurationNames.joinToString { root -> "'$root'" } + ")"
                    }
            }
            .distinct()
            .sorted()
        require(violations.isEmpty()) { violations.joinToString(separator = "; ") }
    }

    private fun KotlinSourceSet.dependencyConfigurations(): List<Pair<ProjectDependencyScope, String>> = listOf(
        ProjectDependencyScope.Api to apiConfigurationName,
        ProjectDependencyScope.Implementation to implementationConfigurationName,
        ProjectDependencyScope.CompileOnly to compileOnlyConfigurationName,
        ProjectDependencyScope.RuntimeOnly to runtimeOnlyConfigurationName,
    )

    private fun requirePlainProjectDependency(
        moduleId: String,
        sourceSet: String,
        scope: ProjectDependencyScope,
        dependency: ProjectDependency,
    ) {
        val customizations = buildList {
            dependency.targetConfiguration?.let { add("targetConfiguration=$it") }
            if (!dependency.isTransitive) add("transitive=false")
            if (dependency.isEndorsingStrictVersions) add("endorsesStrictVersions")
            if (dependency.artifacts.isNotEmpty()) add("artifacts")
            if (dependency.excludeRules.isNotEmpty()) add("excludeRules")
            if (dependency.capabilitySelectors.isNotEmpty()) add("capabilities")
            if (dependency.attributes.keySet().isNotEmpty()) add("attributes")
        }
        require(customizations.isEmpty()) {
            "Unsupported customized project dependency '$moduleId -> ${dependency.path.removePrefix(":")}' " +
                "[$sourceSet/${scope.policyName}]: ${customizations.joinToString()}"
        }
    }
}
