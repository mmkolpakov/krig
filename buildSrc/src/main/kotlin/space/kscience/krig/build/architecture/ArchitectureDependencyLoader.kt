package space.kscience.krig.build.architecture

import java.io.File

internal const val ARCHITECTURE_FRAGMENT_FORMAT: String = "krig-architecture-module-v1"
internal val PROJECT_DEPENDENCY_HEADER: List<String> =
    listOf("consumer", "dependency", "sourceSet", "scope")

internal object ArchitectureDependencyParser {
    fun parse(file: File, lineNumber: Int, columns: List<String>): ProjectDependencyDeclaration {
        require(columns.size == PROJECT_DEPENDENCY_HEADER.size && columns.none(String::isBlank)) {
            "${file.path}:$lineNumber: expected ${PROJECT_DEPENDENCY_HEADER.size} non-empty TSV columns"
        }
        val consumer = columns[0]
        val dependency = columns[1]
        val sourceSet = columns[2]
        require(isModuleName(consumer)) { "${file.path}:$lineNumber: invalid consumer '$consumer'" }
        require(isModuleName(dependency)) { "${file.path}:$lineNumber: invalid dependency '$dependency'" }
        val scope = requireNotNull(
            enumValues<ProjectDependencyScope>().singleOrNull { it.policyName == columns[3] },
        ) { "${file.path}:$lineNumber: invalid dependency scope '${columns[3]}'" }
        return ProjectDependencyDeclaration(consumer, dependency, sourceSet, scope)
    }

    fun isModuleName(value: String): Boolean =
        value.matches(Regex("[a-z][a-z0-9-]*(?::[a-z][a-z0-9-]*)*"))

    fun isKotlinIdentifier(value: String): Boolean =
        value.isNotEmpty() &&
            (value.first() == '_' || value.first().isLetter()) &&
            value.drop(1).all { it == '_' || it.isLetterOrDigit() }
}

internal object ArchitectureFragmentLoader {
    fun load(files: Set<File>, expectedModules: Set<String>): Set<ProjectDependencyDeclaration> {
        val declarations = linkedSetOf<ProjectDependencyDeclaration>()
        val fragmentModules = linkedSetOf<String>()
        files.sortedBy(File::getPath).forEach { file ->
            require(file.isFile) { "Architecture fragment does not exist: ${file.path}" }
            val lines = file.readLines()
            require(lines.size >= 3) { "${file.path}: incomplete architecture fragment" }
            require(lines[0] == "format\t$ARCHITECTURE_FRAGMENT_FORMAT") {
                "${file.path}: unsupported architecture fragment format"
            }
            val moduleColumns = lines[1].split('\t')
            require(moduleColumns.size == 2 && moduleColumns[0] == "module") {
                "${file.path}:2: expected 'module\\t<name>'"
            }
            val module = moduleColumns[1]
            require(ArchitectureDependencyParser.isModuleName(module)) {
                "${file.path}:2: invalid module '$module'"
            }
            require(module in expectedModules) { "${file.path}:2: unexpected architecture fragment '$module'" }
            require(fragmentModules.add(module)) { "${file.path}:2: duplicate architecture fragment '$module'" }
            require(lines[2].split('\t') == PROJECT_DEPENDENCY_HEADER) {
                "${file.path}: expected TSV header '${PROJECT_DEPENDENCY_HEADER.joinToString("\\t")}'"
            }
            lines.drop(3).forEachIndexed { index, line ->
                if (line.isBlank()) return@forEachIndexed
                require(!line.startsWith('#')) {
                    "${file.path}:${index + 4}: comments are not allowed in fragment rows"
                }
                val declaration = ArchitectureDependencyParser.parse(file, index + 4, line.split('\t'))
                require(declaration.consumer == module) {
                    "${file.path}:${index + 4}: fragment '$module' contains consumer '${declaration.consumer}'"
                }
                require(declarations.add(declaration)) {
                    "${file.path}:${index + 4}: duplicate project dependency declaration '$declaration'"
                }
            }
        }
        val missingModules = expectedModules - fragmentModules
        require(missingModules.isEmpty()) {
            "Missing architecture fragments: ${missingModules.sorted().joinToString()}"
        }
        return declarations
    }
}
