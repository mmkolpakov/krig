package space.kscience.krig.build.architecture

import java.io.File
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ArchitectureFragmentLoaderTest {
    @Test
    fun loadsOneVersionedFragmentPerExpectedModule() {
        val first = fragment("first", "first\tsecond\tcommonMain\tapi\n")
        val second = fragment("second")

        val declarations = ArchitectureFragmentLoader.load(setOf(first, second), setOf("first", "second"))

        assertEquals(
            setOf(
                ProjectDependencyDeclaration(
                    consumer = "first",
                    dependency = "second",
                    sourceSet = "commonMain",
                    scope = ProjectDependencyScope.Api,
                ),
            ),
            declarations,
        )
    }

    @Test
    fun rejectsMissingAndDuplicateModuleFragments() {
        val first = fragment("first")
        val missing = assertFailsWith<IllegalArgumentException> {
            ArchitectureFragmentLoader.load(setOf(first), setOf("first", "second"))
        }
        assertTrue(missing.message.orEmpty().contains("Missing architecture fragments: second"))

        val duplicate = assertFailsWith<IllegalArgumentException> {
            ArchitectureFragmentLoader.load(setOf(first, fragment("first")), setOf("first"))
        }
        assertTrue(duplicate.message.orEmpty().contains("duplicate architecture fragment 'first'"))
    }

    @Test
    fun rejectsUnknownFormatAndForeignConsumer() {
        val unknownFormat = fragment("first").apply {
            writeText(readText().replace(ARCHITECTURE_FRAGMENT_FORMAT, "future-format"))
        }
        val formatFailure = assertFailsWith<IllegalArgumentException> {
            ArchitectureFragmentLoader.load(setOf(unknownFormat), setOf("first"))
        }
        assertTrue(formatFailure.message.orEmpty().contains("unsupported architecture fragment format"))

        val foreignConsumer = fragment("first", "second\tfirst\tcommonMain\tapi\n")
        val consumerFailure = assertFailsWith<IllegalArgumentException> {
            ArchitectureFragmentLoader.load(setOf(foreignConsumer), setOf("first"))
        }
        assertTrue(consumerFailure.message.orEmpty().contains("fragment 'first' contains consumer 'second'"))
    }

    private fun fragment(module: String, rows: String = ""): File =
        Files.createTempFile("architecture-fragment-", ".tsv").apply {
            writeText(
                "format\t$ARCHITECTURE_FRAGMENT_FORMAT\n" +
                    "module\t$module\n" +
                    PROJECT_DEPENDENCY_HEADER.joinToString("\t") + "\n" +
                    rows,
            )
        }.toFile()
}
