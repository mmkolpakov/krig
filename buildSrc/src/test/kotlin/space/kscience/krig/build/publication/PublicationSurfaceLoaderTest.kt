package space.kscience.krig.build.publication

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PublicationSurfaceLoaderTest {
    @Test
    fun loadsLfAndCrLfWithoutDerivingCoordinatesFromProjectNames() {
        val lf = load(SURFACE, MODULES)
        val crlf = load(SURFACE.replace("\n", "\r\n"), MODULES.replace("\n", "\r\n"))

        assertEquals(lf, crlf)
        assertEquals(PublicationCoordinate("org.example", "platform-catalog"), lf.bom.coordinate)
        val kmp = lf.entries.single { it.projectPath == ":internal-name" }
        assertEquals(PublicationCoordinate("org.example", "public-api"), kmp.coordinate)
        assertEquals(PublicationCoordinate("org.example", "runtime-jvm"), kmp.jvmCoordinate)
        assertEquals("jvm", kmp.jvmPublicationName)
    }

    @Test
    fun repositoryManifestExactlyCoversArchitectureLibraries() {
        val root = generateSequence(File(System.getProperty("user.dir")).canonicalFile) { it.parentFile }
            .first { it.resolve("config/publication-surface.tsv").isFile }
        val surface = PublicationSurfaceLoader.load(
            root.resolve("config/publication-surface.tsv"),
            root.resolve("config/architecture/modules.tsv"),
        )

        assertEquals(21, surface.entries.size)
        assertEquals(16, surface.libraries.count { it.kind == PublicationKind.KMP_ROOT })
        assertEquals(4, surface.libraries.count { it.kind == PublicationKind.JVM_DIRECT })
        assertEquals(1, surface.entries.count { it.kind == PublicationKind.PLATFORM_BOM })
        assertEquals(":krig-bom", surface.bom.projectPath)
        assertEquals(PublicationCoordinate("space.kscience", "krig-bom"), surface.bom.coordinate)
    }

    @Test
    fun rejectsBareCarriageReturnAndNul() {
        assertRejected("bare carriage returns", SURFACE.replaceFirst('\n', '\r'))
        assertRejected(
            "NUL bytes",
            SURFACE.toByteArray(StandardCharsets.UTF_8).let { bytes ->
                bytes.copyOf(bytes.size + 1).also { it[it.lastIndex] = 0 }
            },
        )
        assertRejected("bare carriage returns", SURFACE, MODULES.replaceFirst('\n', '\r'))
    }

    @Test
    fun rejectsMalformedUtf8() {
        val bytes = SURFACE.toByteArray(StandardCharsets.UTF_8) + byteArrayOf(0xC3.toByte(), 0x28)

        assertRejected("not valid UTF-8", bytes)
    }

    @Test
    fun rejectsMissingAndOversizedPolicyFiles() {
        val directory = Files.createTempDirectory("krig-publication-surface-missing-").toFile()
        try {
            val surfaceFile = directory.resolve("publication-surface.tsv").apply {
                writeText(SURFACE, StandardCharsets.UTF_8)
            }
            val modulesFile = directory.resolve("modules.tsv").apply {
                writeText(MODULES, StandardCharsets.UTF_8)
            }
            assertFailure("Policy file does not exist") {
                PublicationSurfaceLoader.load(directory.resolve("missing-surface.tsv"), modulesFile)
            }
            assertFailure("Policy file does not exist") {
                PublicationSurfaceLoader.load(surfaceFile, directory.resolve("missing-modules.tsv"))
            }
        } finally {
            directory.deleteRecursively()
        }

        val oversized = ByteArray(1024 * 1024 + 1)
        assertFailure("policy exceeds") {
            withFiles(
                oversized,
                MODULES.toByteArray(StandardCharsets.UTF_8),
                PublicationSurfaceLoader::load,
            )
        }
        assertFailure("policy exceeds") {
            withFiles(
                SURFACE.toByteArray(StandardCharsets.UTF_8),
                oversized,
                PublicationSurfaceLoader::load,
            )
        }
    }

    @Test
    fun rejectsMalformedTableShapes() {
        val header = SURFACE.substringBefore('\n')
        val mutations = listOf(
            "policy file is empty" to "",
            "publication surface is empty" to "$header\n",
            "expected TSV header" to SURFACE.replaceFirst("project\tkind", "module\tkind"),
            "blank rows are not allowed" to SURFACE.replaceFirst("$header\n", "$header\n\n"),
            "comments are not allowed" to SURFACE.replaceFirst("$header\n", "$header\n# comment\n"),
            "expected 7 non-empty TSV columns" to SURFACE.replace("\tmaven\tlab-runtime\n", "\tmaven\n"),
            "expected 7 non-empty TSV columns" to SURFACE.replace(
                "\tmaven\tlab-runtime\n",
                "\tmaven\tlab-runtime\textra\n",
            ),
            "expected 7 non-empty TSV columns" to SURFACE.replace("\torg.example\tpublic-api", "\t \tpublic-api"),
        )

        mutations.forEach { (message, surface) -> assertRejected(message, surface) }
        assertRejected(
            "expected TSV header",
            SURFACE,
            MODULES.replaceFirst("module\tkind\tlayer", "project\tkind\tlayer"),
        )
    }

    @Test
    fun requiresCanonicalProjectPathOrdering() {
        val rows = SURFACE.lines().filter(String::isNotEmpty).toMutableList()
        val second = rows[1]
        rows[1] = rows[2]
        rows[2] = second

        assertRejected("sorted canonically", rows.joinToString("\n", postfix = "\n"))
    }

    @Test
    fun rejectsDuplicateProjectPathAndRootCoordinate() {
        val duplicateProject = SURFACE.replace(
            ":jvm-lib\tJVM_DIRECT\tmaven\torg.example\tlab-runtime\tmaven\tlab-runtime",
            ":internal-name\tJVM_DIRECT\tmaven\torg.example\tz-runtime\tmaven\tz-runtime",
        )
        assertRejected("project paths must be unique", duplicateProject)

        val duplicateCoordinate = SURFACE.replace(
            ":jvm-lib\tJVM_DIRECT\tmaven\torg.example\tlab-runtime\tmaven\tlab-runtime",
            ":jvm-lib\tJVM_DIRECT\tmaven\torg.example\tpublic-api\tmaven\tpublic-api",
        )
        assertRejected("root G:A coordinates must be unique", duplicateCoordinate)
    }

    @Test
    fun requiresExactlyOneBom() {
        val withoutBom = SURFACE.trimEnd().lineSequence()
            .filterNot { it.startsWith(":bom\t") }
            .joinToString("\n", postfix = "\n")
        assertRejected("exactly one PLATFORM_BOM", withoutBom)

        val withTwoBoms = SURFACE.replace(
            ":jvm-lib\tJVM_DIRECT\tmaven\torg.example\tlab-runtime\tmaven\tlab-runtime",
            ":jvm-lib\tPLATFORM_BOM\tbom\torg.example\tsecond-platform\t-\t-",
        )
        assertRejected("exactly one PLATFORM_BOM", withTwoBoms)
    }

    @Test
    fun validatesExactKindPublicationAndJvmMapping() {
        val mutations = listOf(
            "KMP_ROOT publication" to SURFACE.replace("KMP_ROOT\tkotlinMultiplatform", "KMP_ROOT\tmaven"),
            "KMP_ROOT must explicitly map" to SURFACE.replace("\tjvm\truntime-jvm", "\tdesktop\truntime-jvm"),
            "KMP_ROOT must explicitly map" to SURFACE.replace("\tjvm\truntime-jvm", "\tjvm\t-"),
            "KMP_ROOT JVM artifact must differ" to SURFACE.replace("\tjvm\truntime-jvm", "\tjvm\tpublic-api"),
            "JVM_DIRECT publication" to SURFACE.replace("JVM_DIRECT\tmaven", "JVM_DIRECT\tkotlinMultiplatform"),
            "JVM_DIRECT must explicitly repeat" to SURFACE.replace("\tmaven\tlab-runtime", "\tjvm\tlab-runtime"),
            "JVM_DIRECT must explicitly repeat" to SURFACE.replace("\tmaven\tlab-runtime", "\tmaven\tlab-runtime-jvm"),
            "PLATFORM_BOM publication" to SURFACE.replace("PLATFORM_BOM\tbom", "PLATFORM_BOM\tplatform"),
            "PLATFORM_BOM must not declare" to SURFACE.replace("platform-catalog\t-\t-", "platform-catalog\tbom\t-"),
            "PLATFORM_BOM must not declare" to SURFACE.replace(
                "platform-catalog\t-\t-",
                "platform-catalog\t-\tplatform-catalog",
            ),
        )

        mutations.forEach { (message, surface) -> assertRejected(message, surface) }
    }

    @Test
    fun rejectsInvalidPublicationFields() {
        val mutations = listOf(
            "invalid Gradle project path" to SURFACE.replace(":internal-name\t", ":internal_name\t"),
            "invalid publication kind" to SURFACE.replace("\tKMP_ROOT\t", "\tKMP\t"),
            "invalid publication name" to SURFACE.replace("\tkotlinMultiplatform\t", "\tkotlin-multiplatform\t"),
            "invalid Maven group" to SURFACE.replaceFirst("org.example", "org/example"),
            "invalid Maven artifact" to SURFACE.replaceFirst("platform-catalog", "platform/catalog"),
            "invalid JVM publication name" to SURFACE.replace("\tjvm\truntime-jvm", "\tj-vm\truntime-jvm"),
            "invalid Maven JVM artifact" to SURFACE.replaceFirst("runtime-jvm", "runtime/jvm"),
        )

        mutations.forEach { (message, surface) -> assertRejected(message, surface) }
    }

    @Test
    fun rejectsJvmCoordinateOwnedByAnotherProject() {
        val collision = SURFACE.replace(
            ":jvm-lib\tJVM_DIRECT\tmaven\torg.example\tlab-runtime\tmaven\tlab-runtime",
            ":jvm-lib\tKMP_ROOT\tkotlinMultiplatform\torg.example\tlab-runtime\tjvm\truntime-jvm",
        )

        assertRejected("is owned by both", collision)
    }

    @Test
    fun requiresExactArchitectureLibraryJoinAndPlatformBomKind() {
        val missing = MODULES.replace("internal-name\tlibrary\tL0\n", "")
        assertRejected("unexpected=:internal-name", SURFACE, missing)

        val extra = MODULES.replace(
            "jvm-lib\tlibrary\tL1",
            "jvm-lib\tlibrary\tL1\nmissing-lib\tlibrary\tL2",
        )
        assertRejected("missing=:missing-lib", SURFACE, extra)

        val bomIsNotPlatform = MODULES.replace("bom\tplatform\t-", "bom\tbuild\t-")
        assertRejected("must be a platform", SURFACE, bomIsNotPlatform)
    }

    @Test
    fun rejectsInvalidAndDuplicateArchitectureModules() {
        val invalidModule = MODULES.replace("internal-name\tlibrary", "internal_name\tlibrary")
        assertRejected("invalid module id", SURFACE, invalidModule)

        val duplicateModule = MODULES.replace(
            "jvm-lib\tlibrary\tL1",
            "internal-name\tlibrary\tL0\njvm-lib\tlibrary\tL1",
        )
        assertRejected("duplicate module 'internal-name'", SURFACE, duplicateModule)
    }

    @Test
    fun requiresOneCanonicalGroup() {
        val mixedGroups = SURFACE.replace("org.example\tlab-runtime", "other.example\tlab-runtime")

        assertRejected("all public root coordinates must use BOM group", mixedGroups)
    }

    private fun load(surface: String, modules: String): PublicationSurface = withFiles(
        surface.toByteArray(StandardCharsets.UTF_8),
        modules.toByteArray(StandardCharsets.UTF_8),
        PublicationSurfaceLoader::load,
    )

    private fun assertRejected(expected: String, surface: String, modules: String = MODULES) {
        assertRejected(expected, surface.toByteArray(StandardCharsets.UTF_8), modules)
    }

    private fun assertRejected(expected: String, surface: ByteArray, modules: String = MODULES) {
        assertFailure(expected) {
            withFiles(surface, modules.toByteArray(StandardCharsets.UTF_8), PublicationSurfaceLoader::load)
        }
    }

    private fun assertFailure(expected: String, block: () -> Any?) {
        val failure = assertFailsWith<IllegalArgumentException> { block() }
        assertTrue(
            failure.message.orEmpty().contains(expected),
            "Expected '$expected' in '${failure.message}'",
        )
    }

    private fun <T> withFiles(
        surface: ByteArray,
        modules: ByteArray,
        block: (File, File) -> T,
    ): T {
        val directory = Files.createTempDirectory("krig-publication-surface-").toFile()
        return try {
            val surfaceFile = directory.resolve("publication-surface.tsv").apply { writeBytes(surface) }
            val modulesFile = directory.resolve("modules.tsv").apply { writeBytes(modules) }
            block(surfaceFile, modulesFile)
        } finally {
            directory.deleteRecursively()
        }
    }

    private companion object {
        val SURFACE: String = """
            project	kind	publication	group	artifact	jvmPublication	jvmArtifact
            :bom	PLATFORM_BOM	bom	org.example	platform-catalog	-	-
            :internal-name	KMP_ROOT	kotlinMultiplatform	org.example	public-api	jvm	runtime-jvm
            :jvm-lib	JVM_DIRECT	maven	org.example	lab-runtime	maven	lab-runtime
        """.trimIndent() + "\n"

        val MODULES: String = """
            module	kind	layer
            internal-name	library	L0
            jvm-lib	library	L1
            bom	platform	-
        """.trimIndent() + "\n"
    }
}
