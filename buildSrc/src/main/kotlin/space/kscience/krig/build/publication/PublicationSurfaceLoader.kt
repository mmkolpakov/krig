package space.kscience.krig.build.publication

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

internal object PublicationSurfaceLoader {
    fun load(surfaceFile: File, architectureModulesFile: File): PublicationSurface {
        val entries = rows(surfaceFile, SURFACE_HEADER).mapIndexed { index, columns ->
            parseEntry(surfaceFile, index + 2, columns)
        }
        require(entries.isNotEmpty()) { "${surfaceFile.path}: publication surface is empty" }
        require(entries == entries.sorted()) {
            "${surfaceFile.path}: publication rows must be sorted canonically by project path"
        }
        require(entries.map(PublicationSurfaceEntry::projectPath).toSet().size == entries.size) {
            "${surfaceFile.path}: publication project paths must be unique"
        }
        require(entries.map(PublicationSurfaceEntry::coordinate).toSet().size == entries.size) {
            "${surfaceFile.path}: publication root G:A coordinates must be unique"
        }

        val bomEntries = entries.filter { it.kind == PublicationKind.PLATFORM_BOM }
        require(bomEntries.size == 1) {
            "${surfaceFile.path}: publication surface must contain exactly one PLATFORM_BOM entry"
        }
        val bom = bomEntries.single()
        require(entries.all { it.coordinate.group == bom.coordinate.group }) {
            "${surfaceFile.path}: all public root coordinates must use BOM group '${bom.coordinate.group}'"
        }
        verifyJvmCoordinateOwnership(surfaceFile, entries)
        verifyArchitectureJoin(surfaceFile, architectureModulesFile, entries, bom)
        return PublicationSurface(entries)
    }

    private fun parseEntry(file: File, line: Int, columns: List<String>): PublicationSurfaceEntry {
        val projectPath = columns[0]
        require(projectPath.matches(PROJECT_PATH_PATTERN)) {
            "${file.path}:$line: invalid Gradle project path '$projectPath'"
        }
        val kind = runCatching { PublicationKind.valueOf(columns[1]) }.getOrElse {
            throw IllegalArgumentException("${file.path}:$line: invalid publication kind '${columns[1]}'", it)
        }
        val publicationName = columns[2]
        require(publicationName.matches(PUBLICATION_NAME_PATTERN)) {
            "${file.path}:$line: invalid publication name '$publicationName'"
        }
        val coordinate = PublicationCoordinate(
            group = columns[3].also { validateMavenPart(file, line, "group", it) },
            artifact = columns[4].also { validateMavenPart(file, line, "artifact", it) },
        )
        val jvmPublicationName = columns[5].optionalValue()
        val jvmArtifact = columns[6].optionalValue()
        if (jvmPublicationName != null) {
            require(jvmPublicationName.matches(PUBLICATION_NAME_PATTERN)) {
                "${file.path}:$line: invalid JVM publication name '$jvmPublicationName'"
            }
        }
        if (jvmArtifact != null) validateMavenPart(file, line, "JVM artifact", jvmArtifact)

        when (kind) {
            PublicationKind.KMP_ROOT -> {
                require(publicationName == KMP_ROOT_PUBLICATION) {
                    "${file.path}:$line: KMP_ROOT publication must be '$KMP_ROOT_PUBLICATION'"
                }
                require(jvmPublicationName == KMP_JVM_PUBLICATION && jvmArtifact != null) {
                    "${file.path}:$line: KMP_ROOT must explicitly map JVM publication '$KMP_JVM_PUBLICATION' " +
                        "and its artifact"
                }
                require(jvmArtifact != coordinate.artifact) {
                    "${file.path}:$line: KMP_ROOT JVM artifact must differ from its root artifact"
                }
            }

            PublicationKind.JVM_DIRECT -> {
                require(publicationName == JVM_DIRECT_PUBLICATION) {
                    "${file.path}:$line: JVM_DIRECT publication must be '$JVM_DIRECT_PUBLICATION'"
                }
                require(jvmPublicationName == publicationName && jvmArtifact == coordinate.artifact) {
                    "${file.path}:$line: JVM_DIRECT must explicitly repeat its publication and artifact as JVM mapping"
                }
            }

            PublicationKind.PLATFORM_BOM -> {
                require(publicationName == BOM_PUBLICATION) {
                    "${file.path}:$line: PLATFORM_BOM publication must be '$BOM_PUBLICATION'"
                }
                require(jvmPublicationName == null && jvmArtifact == null) {
                    "${file.path}:$line: PLATFORM_BOM must not declare a JVM mapping"
                }
            }
        }

        return PublicationSurfaceEntry(
            projectPath = projectPath,
            kind = kind,
            publicationName = publicationName,
            coordinate = coordinate,
            jvmPublicationName = jvmPublicationName,
            jvmCoordinate = jvmArtifact?.let { PublicationCoordinate(coordinate.group, it) },
        )
    }

    private fun verifyJvmCoordinateOwnership(file: File, entries: List<PublicationSurfaceEntry>) {
        val owners = linkedMapOf<PublicationCoordinate, PublicationSurfaceEntry>()
        entries.forEach { entry ->
            sequenceOf(entry.coordinate, entry.jvmCoordinate).filterNotNull().distinct().forEach { coordinate ->
                val previous = owners.putIfAbsent(coordinate, entry)
                require(previous == null || previous == entry) {
                    "${file.path}: public coordinate '$coordinate' is owned by both " +
                        "'${previous?.projectPath}' and '${entry.projectPath}'"
                }
            }
        }
    }

    private fun verifyArchitectureJoin(
        surfaceFile: File,
        modulesFile: File,
        entries: List<PublicationSurfaceEntry>,
        bom: PublicationSurfaceEntry,
    ) {
        val modules = linkedMapOf<String, String>()
        rows(modulesFile, MODULES_HEADER).forEachIndexed { index, columns ->
            val module = columns[0]
            require(module.matches(MODULE_ID_PATTERN)) {
                "${modulesFile.path}:${index + 2}: invalid module id '$module'"
            }
            require(modules.put(module, columns[1]) == null) {
                "${modulesFile.path}:${index + 2}: duplicate module '$module'"
            }
        }

        val architectureLibraries = modules.asSequence()
            .filter { it.value == ARCHITECTURE_LIBRARY_KIND }
            .map { (module, _) -> ":$module" }
            .toSortedSet()
        val publishedLibraries = entries.asSequence()
            .filter { it.kind != PublicationKind.PLATFORM_BOM }
            .map(PublicationSurfaceEntry::projectPath)
            .toSortedSet()
        val missing = architectureLibraries - publishedLibraries
        val unexpected = publishedLibraries - architectureLibraries
        require(missing.isEmpty() && unexpected.isEmpty()) {
            buildString {
                append("${surfaceFile.path}: publication libraries must exactly match architecture libraries")
                if (missing.isNotEmpty()) append("; missing=${missing.joinToString()}")
                if (unexpected.isNotEmpty()) append("; unexpected=${unexpected.joinToString()}")
            }
        }

        val bomModule = bom.projectPath.removePrefix(":")
        require(modules[bomModule] == ARCHITECTURE_PLATFORM_KIND) {
            "${surfaceFile.path}: PLATFORM_BOM project '${bom.projectPath}' must be a platform in ${modulesFile.path}"
        }
    }

    private fun rows(file: File, expectedHeader: List<String>): List<List<String>> {
        val lines = normalizedLines(file)
        require(lines.isNotEmpty()) { "${file.path}: policy file is empty" }
        require(lines.first().split('\t') == expectedHeader) {
            "${file.path}: expected TSV header '${expectedHeader.joinToString("\\t")}'"
        }
        return lines.drop(1).mapIndexed { index, line ->
            require(line.isNotEmpty()) { "${file.path}:${index + 2}: blank rows are not allowed" }
            require(!line.startsWith('#')) { "${file.path}:${index + 2}: comments are not allowed" }
            val columns = line.split('\t')
            require(columns.size == expectedHeader.size && columns.none(String::isBlank)) {
                "${file.path}:${index + 2}: expected ${expectedHeader.size} non-empty TSV columns"
            }
            columns
        }
    }

    private fun normalizedLines(file: File): List<String> {
        require(file.isFile) { "Policy file does not exist: ${file.path}" }
        val bytes = file.readBytes()
        require(bytes.size <= MAX_POLICY_BYTES) { "${file.path}: policy exceeds $MAX_POLICY_BYTES bytes" }
        require(bytes.none { it == 0.toByte() }) { "${file.path}: NUL bytes are not allowed" }
        val text = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (failure: CharacterCodingException) {
            throw IllegalArgumentException("${file.path}: policy is not valid UTF-8", failure)
        }
        val normalized = text.replace("\r\n", "\n")
        require('\r' !in normalized) { "${file.path}: bare carriage returns are not allowed" }
        val lines = normalized.split('\n')
        return if (lines.lastOrNull().isNullOrEmpty()) lines.dropLast(1) else lines
    }

    private fun validateMavenPart(file: File, line: Int, label: String, value: String) {
        require(value.matches(MAVEN_PART_PATTERN)) {
            "${file.path}:$line: invalid Maven $label '$value'"
        }
    }

    private fun String.optionalValue(): String? = takeUnless { it == ABSENT_VALUE }

    private val SURFACE_HEADER: List<String> = listOf(
        "project",
        "kind",
        "publication",
        "group",
        "artifact",
        "jvmPublication",
        "jvmArtifact",
    )
    private val MODULES_HEADER: List<String> = listOf("module", "kind", "layer")
    private val PROJECT_PATH_PATTERN: Regex = Regex("(?::[a-z][a-z0-9-]*)+")
    private val MODULE_ID_PATTERN: Regex = Regex("[a-z][a-z0-9-]*(?::[a-z][a-z0-9-]*)*")
    private val PUBLICATION_NAME_PATTERN: Regex = Regex("[a-z][A-Za-z0-9]*")
    private val MAVEN_PART_PATTERN: Regex = Regex("[A-Za-z0-9_.-]+")
    private const val KMP_ROOT_PUBLICATION: String = "kotlinMultiplatform"
    private const val KMP_JVM_PUBLICATION: String = "jvm"
    private const val JVM_DIRECT_PUBLICATION: String = "maven"
    private const val BOM_PUBLICATION: String = "bom"
    private const val ARCHITECTURE_LIBRARY_KIND: String = "library"
    private const val ARCHITECTURE_PLATFORM_KIND: String = "platform"
    private const val ABSENT_VALUE: String = "-"
    private const val MAX_POLICY_BYTES: Int = 1024 * 1024
}
