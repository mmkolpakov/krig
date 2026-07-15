package space.kscience.krig.build.architecture

import java.io.File

internal object ArchitecturePolicyLoader {
    fun load(directory: File): ArchitecturePolicy {
        val modules = loadModules(directory.resolve("modules.tsv"))
        val edges = loadEdges(directory.resolve("edges.tsv"), modules)
        val packages = loadPackages(directory.resolve("packages.tsv"), modules)
        return ArchitecturePolicy(modules, edges, packages)
    }

    private fun loadModules(file: File): Map<String, ModulePolicy> {
        val rows = rows(file, listOf("module", "kind", "layer"))
        val modules = linkedMapOf<String, ModulePolicy>()
        rows.forEachIndexed { index, columns ->
            val name = columns[0]
            require(name.matches(Regex("[a-z][a-z0-9-]*(?::[a-z][a-z0-9-]*)*"))) {
                "${file.path}:${index + 2}: invalid module name '$name'"
            }
            val kind = enumValue<ModuleKind>(file, index, columns[1])
            val layer = columns[2].takeUnless { it == "-" }?.let {
                enumValue<ArchitectureLayer>(file, index, it)
            }
            require((kind == ModuleKind.Library) == (layer != null)) {
                "${file.path}:${index + 2}: only library modules must declare a layer"
            }
            require(modules.put(name, ModulePolicy(name, kind, layer)) == null) {
                "${file.path}:${index + 2}: duplicate module '$name'"
            }
        }
        require(modules.isNotEmpty()) { "${file.path}: module policy is empty" }
        return modules
    }

    private fun loadEdges(file: File, modules: Map<String, ModulePolicy>): Set<ModuleEdge> {
        val result = linkedSetOf<ModuleEdge>()
        rows(file, listOf("consumer", "dependency")).forEachIndexed { index, columns ->
            val edge = ModuleEdge(columns[0], columns[1])
            require(edge.consumer in modules) {
                "${file.path}:${index + 2}: unknown consumer '${edge.consumer}'"
            }
            require(edge.dependency in modules) {
                "${file.path}:${index + 2}: unknown dependency '${edge.dependency}'"
            }
            require(edge.consumer != edge.dependency) {
                "${file.path}:${index + 2}: self dependency '${edge.consumer}'"
            }
            require(modules.getValue(edge.consumer).kind == ModuleKind.Library) {
                "${file.path}:${index + 2}: consumer '${edge.consumer}' is not a library"
            }
            require(modules.getValue(edge.dependency).kind == ModuleKind.Library) {
                "${file.path}:${index + 2}: dependency '${edge.dependency}' is not a library"
            }
            require(result.add(edge)) { "${file.path}:${index + 2}: duplicate edge '$edge'" }
        }
        return result
    }

    private fun loadPackages(
        file: File,
        modules: Map<String, ModulePolicy>,
    ): Map<String, PackagePolicy> {
        val result = linkedMapOf<String, PackagePolicy>()
        rows(file, listOf("package", "owner", "contributors")).forEachIndexed { index, columns ->
            val packageName = columns[0]
            require(packageName.split('.').all(::isKotlinIdentifier)) {
                "${file.path}:${index + 2}: invalid package '$packageName'"
            }
            val owner = columns[1]
            val contributorEntries = columns[2].split(',').map(String::trim)
            val contributors = contributorEntries.toCollection(linkedSetOf())
            require(contributorEntries.size == contributors.size) {
                "${file.path}:${index + 2}: duplicate package contributor"
            }
            require(contributors.isNotEmpty()) {
                "${file.path}:${index + 2}: a package needs at least one contributor"
            }
            require(owner in contributors) {
                "${file.path}:${index + 2}: owner '$owner' is not a contributor"
            }
            (contributors + owner).forEach { module ->
                require(modules[module]?.kind == ModuleKind.Library) {
                    "${file.path}:${index + 2}: unknown library contributor '$module'"
                }
            }
            val policy = PackagePolicy(packageName, owner, contributors)
            require(result.put(packageName, policy) == null) {
                "${file.path}:${index + 2}: duplicate package '$packageName'"
            }
        }
        return result
    }

    private fun rows(file: File, header: List<String>): List<List<String>> {
        require(file.isFile) { "Architecture policy file does not exist: ${file.path}" }
        val lines = file.readLines()
        require(lines.isNotEmpty()) { "Architecture policy file is empty: ${file.path}" }
        require(lines.first().split('\t') == header) {
            "${file.path}: expected TSV header '${header.joinToString("\\t")}'"
        }
        return lines.drop(1).mapIndexedNotNull { index, line ->
            if (line.isBlank()) return@mapIndexedNotNull null
            require(!line.startsWith('#')) {
                "${file.path}:${index + 2}: comments are not allowed in policy rows"
            }
            val columns = line.split('\t')
            require(columns.size == header.size && columns.none(String::isBlank)) {
                "${file.path}:${index + 2}: expected ${header.size} non-empty TSV columns"
            }
            columns
        }
    }

    private inline fun <reified T : Enum<T>> enumValue(file: File, index: Int, value: String): T =
        enumValues<T>().singleOrNull { it.name.equals(value, ignoreCase = true) }
            ?: error("${file.path}:${index + 2}: invalid ${T::class.simpleName} '$value'")

    private fun isKotlinIdentifier(value: String): Boolean =
        value.isNotEmpty() &&
            (value.first() == '_' || value.first().isLetter()) &&
            value.drop(1).all { it == '_' || it.isLetterOrDigit() }
}
