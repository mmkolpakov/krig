package space.kscience.krig.build.architecture

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.nio.charset.StandardCharsets

@CacheableTask
internal abstract class CheckArchitectureTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val policyDirectory: DirectoryProperty

    @get:Input
    abstract val moduleNames: ListProperty<String>

    @get:Input
    abstract val declaredEdges: ListProperty<String>

    @get:Input
    abstract val kotlinModuleNames: ListProperty<String>

    @get:Input
    abstract val moduleRoots: MapProperty<String, String>

    @get:Input
    abstract val sourceRoots: MapProperty<String, String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceFiles: ConfigurableFileCollection

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
        roots.forEachIndexed { index, sourceRoot ->
            roots.drop(index + 1)
                .filter { other ->
                    sourceRoot.module != other.module &&
                        (sourceRoot.path.startsWith(other.path) || other.path.startsWith(sourceRoot.path))
                }
                .forEach { other ->
                    sourceProblems += "Overlapping Kotlin source roots belong to different modules: " +
                        "${sourceRoot.module}:${root.relativize(sourceRoot.path)} and " +
                        "${other.module}:${root.relativize(other.path)}"
                }
        }

        val missingKotlinModels = libraryModules - kotlinModuleNames.get().toSet()
        if (missingKotlinModels.isNotEmpty()) {
            sourceProblems += "Library modules without a Kotlin source-set model: " +
                missingKotlinModels.sorted().joinToString()
        }

        val packageContributors = linkedMapOf<String, MutableSet<String>>()
        val moduleRootMappings = moduleRoots.get().entries.map { (relativePath, module) ->
            SourceRoot(root.resolve(relativePath).normalize(), module)
        }.sortedByDescending { it.path.nameCount }
        val javaSources = productionJavaFiles.files
            .filter { it.isFile && it.extension == "java" }
            .mapNotNull { file ->
                val path = file.toPath().toAbsolutePath().normalize()
                val module = moduleRootMappings.firstOrNull { path.startsWith(it.path) }?.module
                when {
                    module == null -> {
                        sourceProblems += "Java source is outside declared module roots: ${relative(root, file)}"
                        null
                    }
                    module !in libraryModules -> null
                    else -> relative(root, file)
                }
            }
            .sorted()
        if (javaSources.isNotEmpty()) {
            sourceProblems += "Production Java package checking is not implemented; Java sources: ${javaSources.joinToString()}"
        }
        sourceFiles.files.asSequence()
            .filter { it.isFile && it.extension == "kt" }
            .sortedBy { it.invariantSeparatorsPath }
            .forEach { sourceFile ->
                val path = sourceFile.toPath().toAbsolutePath().normalize()
                val sourceRoot = roots.firstOrNull { path.startsWith(it.path) }
                if (sourceRoot == null) {
                    sourceProblems += "Source file is outside declared Kotlin source roots: ${relative(root, sourceFile)}"
                    return@forEach
                }
                if (sourceRoot.module !in libraryModules) return@forEach
                val packageName = runCatching {
                    KotlinPackageParser.parse(sourceFile.readText(StandardCharsets.UTF_8))
                }.getOrElse { error ->
                    sourceProblems += "Cannot parse package in ${relative(root, sourceFile)}: ${error.message}"
                    return@forEach
                }
                if (packageName == null) {
                    sourceProblems += "Production Kotlin file has no package: ${relative(root, sourceFile)}"
                } else {
                    packageContributors.getOrPut(packageName, ::linkedSetOf) += sourceRoot.module
                }
            }

        val edges = declaredEdges.get().mapTo(linkedSetOf()) { value ->
            val columns = value.split('\t')
            require(columns.size == 2) { "Invalid collected project edge '$value'" }
            ModuleEdge(columns[0], columns[1])
        }.filterTo(linkedSetOf()) { it.consumer in libraryModules }

        val snapshot = ArchitectureSnapshot(
            modules = moduleNames.get().toSet(),
            edges = edges,
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
        val edges = snapshot.edges.sorted()
        val splitPackages = snapshot.packageContributors
            .filterValues { it.size > 1 }
            .toSortedMap()
        val directory = reportDirectory.get().asFile.apply { mkdirs() }
        directory.resolve("report.json").writeText(
            buildString {
                appendLine("{")
                appendLine("  \"status\": \"${if (errors.isEmpty()) "PASS" else "FAIL"}\",")
                appendLine("  \"counts\": {")
                appendLine("    \"modules\": ${modules.size},")
                appendLine("    \"libraryModules\": ${policy.libraryModules.size},")
                appendLine("    \"edges\": ${edges.size},")
                appendLine("    \"packages\": ${snapshot.packageContributors.size},")
                appendLine("    \"splitPackages\": ${splitPackages.size}")
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
                appendLine("  \"edges\": [")
                edges.forEachIndexed { index, edge ->
                    val suffix = if (index == edges.lastIndex) "" else ","
                    appendLine(
                        "    {\"consumer\": ${jsonValue(edge.consumer)}, " +
                            "\"dependency\": ${jsonValue(edge.dependency)}}$suffix",
                    )
                }
                appendLine("  ],")
                appendLine("  \"splitPackages\": [")
                splitPackages.entries.forEachIndexed { index, (packageName, contributors) ->
                    val suffix = if (index == splitPackages.size - 1) "" else ","
                    val contributorValues = contributors.sorted().joinToString(", ", transform = ::jsonValue)
                    appendLine(
                        "    {\"package\": ${jsonValue(packageName)}, " +
                            "\"owner\": ${jsonValue(policy.splitPackages[packageName]?.owner)}, " +
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
                appendLine("| Direct production edges | ${edges.size} |")
                appendLine("| Production packages | ${snapshot.packageContributors.size} |")
                appendLine("| Split packages | ${splitPackages.size} |")
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
                appendLine("## Direct production edges")
                appendLine()
                appendLine("| Consumer | Dependency |")
                appendLine("|---|---|")
                edges.forEach { edge -> appendLine("| ${edge.consumer} | ${edge.dependency} |") }
                appendLine()
                appendLine("## Split packages")
                appendLine()
                appendLine("| Package | Owner | Contributors |")
                appendLine("|---|---|---|")
                splitPackages.forEach { (packageName, contributors) ->
                    appendLine(
                        "| $packageName | ${policy.splitPackages[packageName]?.owner ?: "unclassified"} | " +
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

    private fun jsonValue(value: String?): String = value?.let { "\"${jsonEscape(it)}\"" } ?: "null"

    private fun jsonEscape(value: String): String = buildString(value.length) {
        value.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(char)
            }
        }
    }

    private data class SourceRoot(val path: java.nio.file.Path, val module: String)
}
