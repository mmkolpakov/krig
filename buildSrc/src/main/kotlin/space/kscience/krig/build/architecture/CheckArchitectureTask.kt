package space.kscience.krig.build.architecture

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileCollection
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.nio.charset.StandardCharsets

internal class KotlinModuleSources(
    @get:Input
    val moduleName: String,
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val files: FileCollection,
)

@CacheableTask
internal abstract class CheckArchitectureTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val policyDirectory: DirectoryProperty

    @get:Input
    abstract val moduleNames: ListProperty<String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val dependencyFragments: ConfigurableFileCollection

    @get:Input
    abstract val kotlinModuleNames: ListProperty<String>

    @get:Input
    abstract val moduleRoots: MapProperty<String, String>

    @get:Input
    abstract val sourceRoots: MapProperty<String, String>

    @get:Input
    abstract val javaSourceRoots: MapProperty<String, String>

    @get:Nested
    abstract val productionKotlinSources: ListProperty<KotlinModuleSources>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val productionJavaFiles: ConfigurableFileCollection

    @get:Internal
    abstract val repositoryDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val reportDirectory: DirectoryProperty

    @TaskAction
    fun checkArchitecture() {
        val policy = ArchitecturePolicyLoader.load(policyDirectory.get().asFile)
        val libraryModules = policy.libraryModules
        val sourceProblems = mutableListOf<String>()
        val root = repositoryDirectory.get().asFile.toPath().toAbsolutePath().normalize()
        val roots = sourceRoots.get().entries.map { (relativePath, module) ->
            SourceRoot(root.resolve(relativePath).normalize(), module)
        }.sortedByDescending { it.path.nameCount }
        val javaRoots = javaSourceRoots.get().entries.map { (relativePath, module) ->
            SourceRoot(root.resolve(relativePath).normalize(), module)
        }.sortedByDescending { it.path.nameCount }
        val logicalRoots = roots + javaRoots
        logicalRoots.forEachIndexed { index, sourceRoot ->
            logicalRoots.drop(index + 1)
                .filter { other ->
                    sourceRoot.module != other.module &&
                        (sourceRoot.path.startsWith(other.path) || other.path.startsWith(sourceRoot.path))
                }
                .forEach { other ->
                    sourceProblems += "Overlapping production source roots belong to different modules: " +
                        "${sourceRoot.module}:${root.relativize(sourceRoot.path)} and " +
                        "${other.module}:${root.relativize(other.path)}"
                }
        }

        val missingKotlinModels = libraryModules - kotlinModuleNames.get().toSet()
        if (missingKotlinModels.isNotEmpty()) {
            sourceProblems += "Library modules without a Kotlin source-set model: " +
                missingKotlinModels.sorted().joinToString()
        }

        val compilationSources = linkedMapOf<java.nio.file.Path, CompilationSource>()
        productionKotlinSources.get().sortedBy(KotlinModuleSources::moduleName).forEach { moduleSources ->
            moduleSources.files.files.asSequence()
                .filter(File::isFile)
                .forEach sourceFileLoop@{ file ->
                    val path = file.toPath().toAbsolutePath().normalize()
                    if (!path.startsWith(root)) {
                        sourceProblems += "Production source for module '${moduleSources.moduleName}' is outside " +
                            "the repository root: ${path.toString().replace(File.separatorChar, '/')}"
                        return@sourceFileLoop
                    }
                    compilationSources.getOrPut(path) { CompilationSource(file) }.modules += moduleSources.moduleName
                }
        }

        val packageContributors = linkedMapOf<String, MutableSet<String>>()
        val moduleRootMappings = moduleRoots.get().entries.map { (relativePath, module) ->
            SourceRoot(root.resolve(relativePath).normalize(), module)
        }.sortedByDescending { it.path.nameCount }
        val sourceSetJavaSources = productionJavaFiles.files
            .distinct()
            .filter { it.isFile && it.extension.equals("java", ignoreCase = true) }
            .mapNotNull { file ->
                val path = file.toPath().toAbsolutePath().normalize()
                val module = javaRoots.firstOrNull { path.startsWith(it.path) }?.module
                    ?: roots.firstOrNull { path.startsWith(it.path) }?.module
                    ?: moduleRootMappings.firstOrNull { path.startsWith(it.path) }?.module
                when (module) {
                    null -> {
                        sourceProblems += "Java source is outside declared module roots: ${relative(root, file)}"
                        null
                    }
                    in libraryModules -> relative(root, file)
                    else -> null
                }
            }
        val compilationJavaSources = compilationSources.values.asSequence()
            .filter { source -> source.file.extension.equals("java", ignoreCase = true) }
            .filter { source -> source.modules.any { module -> module in libraryModules } }
            .map { source -> relative(root, source.file) }
            .toList()
        val javaSources = (sourceSetJavaSources + compilationJavaSources)
            .distinct()
            .sorted()
        if (javaSources.isNotEmpty()) {
            sourceProblems += "Production Java package checking is not implemented; Java sources: ${javaSources.joinToString()}"
        }
        compilationSources.values.asSequence()
            .filter { source -> source.file.extension.lowercase() in KOTLIN_SOURCE_EXTENSIONS }
            .sortedBy { source -> source.file.invariantSeparatorsPath }
            .forEach sourceLoop@{ source ->
                val contributors = source.modules.filterTo(linkedSetOf()) { module -> module in libraryModules }
                if (contributors.isEmpty()) return@sourceLoop
                val sourceFile = source.file
                val packageName = runCatching {
                    KotlinPackageParser.parse(sourceFile.readText(StandardCharsets.UTF_8))
                }.getOrElse { error ->
                    sourceProblems += "Cannot parse package in ${relative(root, sourceFile)}: ${error.message}"
                    return@sourceLoop
                }
                if (packageName == null) {
                    sourceProblems += "Production Kotlin file has no package: ${relative(root, sourceFile)}"
                } else {
                    packageContributors.getOrPut(packageName, ::linkedSetOf) += contributors
                }
            }

        val projectDependencies = ArchitectureFragmentLoader.load(
            files = dependencyFragments.files,
            expectedModules = libraryModules,
        )

        val snapshot = ArchitectureSnapshot(
            modules = moduleNames.get().toSet(),
            projectDependencies = projectDependencies,
            packageContributors = packageContributors.mapValues { it.value.toSet() },
        )
        val verification = ArchitectureVerifier.verify(policy, snapshot)
        val errors = (sourceProblems + verification.errors).distinct().sorted()
        writeReports(policy, snapshot, errors)
        if (errors.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("Architecture baseline check failed:")
                    errors.forEach { appendLine(" - $it") }
                    append("Reports: ${reportDirectory.get().asFile}")
                },
            )
        }
    }

    private fun writeReports(
        policy: ArchitecturePolicy,
        snapshot: ArchitectureSnapshot,
        errors: List<String>,
    ) {
        val modules = snapshot.modules.sorted()
        val projectDependencies = snapshot.projectDependencies.sorted()
        val edges = snapshot.edges.sorted()
        val scopeCounts = enumValues<ProjectDependencyScope>().associateWith { scope ->
            projectDependencies.count { declaration -> declaration.scope == scope }
        }
        val packages = snapshot.packageContributors.toSortedMap()
        val splitPackageCount = packages.count { (_, contributors) -> contributors.size > 1 }
        val directory = reportDirectory.get().asFile.apply { mkdirs() }
        directory.resolve("report.json").writeText(
            buildString {
                appendLine("{")
                appendLine("  \"status\": \"${if (errors.isEmpty()) "PASS" else "FAIL"}\",")
                appendLine("  \"counts\": {")
                appendLine("    \"modules\": ${modules.size},")
                appendLine("    \"libraryModules\": ${policy.libraryModules.size},")
                appendLine("    \"projectDependencyDeclarations\": ${projectDependencies.size},")
                appendLine("    \"edges\": ${edges.size},")
                appendLine("    \"packages\": ${snapshot.packageContributors.size},")
                appendLine("    \"splitPackages\": $splitPackageCount")
                appendLine("  },")
                appendLine("  \"dependencyScopes\": {")
                scopeCounts.entries.forEachIndexed { index, (scope, count) ->
                    val suffix = if (index == scopeCounts.size - 1) "" else ","
                    appendLine("    ${jsonValue(scope.policyName)}: $count$suffix")
                }
                appendLine("  },")
                appendLine("  \"moduleClassifications\": [")
                modules.forEachIndexed { index, module ->
                    val classification = policy.modules[module]
                    val suffix = if (index == modules.lastIndex) "" else ","
                    appendLine(
                        "    {\"module\": ${jsonValue(module)}, " +
                            "\"kind\": ${jsonValue(classification?.kind?.name?.lowercase())}, " +
                            "\"layer\": ${jsonValue(classification?.layer?.name)}}$suffix",
                    )
                }
                appendLine("  ],")
                appendLine("  \"projectDependencies\": [")
                projectDependencies.forEachIndexed { index, declaration ->
                    val suffix = if (index == projectDependencies.lastIndex) "" else ","
                    appendLine(
                        "    {\"consumer\": ${jsonValue(declaration.consumer)}, " +
                            "\"dependency\": ${jsonValue(declaration.dependency)}, " +
                            "\"sourceSet\": ${jsonValue(declaration.sourceSet)}, " +
                            "\"scope\": ${jsonValue(declaration.scope.policyName)}}$suffix",
                    )
                }
                appendLine("  ],")
                appendLine("  \"edges\": [")
                edges.forEachIndexed { index, edge ->
                    val suffix = if (index == edges.lastIndex) "" else ","
                    appendLine(
                        "    {\"consumer\": ${jsonValue(edge.consumer)}, " +
                            "\"dependency\": ${jsonValue(edge.dependency)}}$suffix",
                    )
                }
                appendLine("  ],")
                appendLine("  \"packages\": [")
                packages.entries.forEachIndexed { index, (packageName, contributors) ->
                    val suffix = if (index == packages.size - 1) "" else ","
                    val contributorValues = contributors.sorted().joinToString(", ", transform = ::jsonValue)
                    appendLine(
                        "    {\"package\": ${jsonValue(packageName)}, " +
                            "\"owner\": ${jsonValue(policy.packages[packageName]?.owner)}, " +
                            "\"contributors\": [$contributorValues]}$suffix",
                    )
                }
                appendLine("  ],")
                appendLine("  \"errors\": [")
                errors.forEachIndexed { index, error ->
                    val suffix = if (index == errors.lastIndex) "" else ","
                    appendLine("    ${jsonValue(error)}$suffix")
                }
                appendLine("  ]")
                appendLine("}")
            },
            StandardCharsets.UTF_8,
        )
        directory.resolve("report.md").writeText(
            buildString {
                appendLine("# Architecture baseline")
                appendLine()
                appendLine("Status: **${if (errors.isEmpty()) "PASS" else "FAIL"}**")
                appendLine()
                appendLine("| Metric | Count |")
                appendLine("|---|---:|")
                appendLine("| Gradle modules | ${snapshot.modules.size} |")
                appendLine("| Library modules | ${policy.libraryModules.size} |")
                appendLine("| Direct project dependency declarations | ${projectDependencies.size} |")
                appendLine("| Unique production edges | ${edges.size} |")
                appendLine("| Production packages | ${snapshot.packageContributors.size} |")
                appendLine("| Split packages | $splitPackageCount |")
                appendLine()
                appendLine("## Module classifications")
                appendLine()
                appendLine("| Module | Kind | Layer |")
                appendLine("|---|---|---|")
                modules.forEach { module ->
                    val classification = policy.modules[module]
                    appendLine(
                        "| $module | ${classification?.kind?.name?.lowercase() ?: "unclassified"} | " +
                            "${classification?.layer?.name ?: "-"} |",
                    )
                }
                appendLine()
                appendLine("## Direct project dependency declarations")
                appendLine()
                appendLine("| Consumer | Dependency | Source set | Scope |")
                appendLine("|---|---|---|---|")
                projectDependencies.forEach { declaration ->
                    appendLine(
                        "| ${declaration.consumer} | ${declaration.dependency} | ${declaration.sourceSet} | " +
                            "${declaration.scope.policyName} |",
                    )
                }
                appendLine()
                appendLine("## Derived production edges")
                appendLine()
                appendLine("| Consumer | Dependency |")
                appendLine("|---|---|")
                edges.forEach { edge -> appendLine("| ${edge.consumer} | ${edge.dependency} |") }
                appendLine()
                appendLine("## Production package ownership")
                appendLine()
                appendLine("| Package | Owner | Contributors |")
                appendLine("|---|---|---|")
                packages.forEach { (packageName, contributors) ->
                    appendLine(
                        "| $packageName | ${policy.packages[packageName]?.owner ?: "unclassified"} | " +
                            "${contributors.sorted().joinToString()} |",
                    )
                }
                if (errors.isNotEmpty()) {
                    appendLine()
                    appendLine("## Violations")
                    appendLine()
                    errors.forEach { appendLine("- $it") }
                }
            },
            StandardCharsets.UTF_8,
        )
    }

    private fun relative(root: java.nio.file.Path, file: File): String =
        root.relativize(file.toPath().toAbsolutePath().normalize()).toString().replace(File.separatorChar, '/')

    private fun jsonValue(value: String?): String = value?.let { "\"${escapeJsonString(it)}\"" } ?: "null"

    private data class SourceRoot(val path: java.nio.file.Path, val module: String)

    private data class CompilationSource(
        val file: File,
        val modules: MutableSet<String> = linkedSetOf(),
    )

    private companion object {
        val KOTLIN_SOURCE_EXTENSIONS: Set<String> = setOf("kt", "kts")
    }
}

internal fun escapeJsonString(value: String): String = buildString(value.length) {
    value.forEach { char ->
        when (char) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (char.code < ' '.code) {
                append("\\u").append(char.code.toString(16).padStart(4, '0'))
            } else {
                append(char)
            }
        }
    }
}
