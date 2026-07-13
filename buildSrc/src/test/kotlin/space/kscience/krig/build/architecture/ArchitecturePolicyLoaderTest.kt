package space.kscience.krig.build.architecture

import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ArchitecturePolicyLoaderTest {
    @Test
    fun loadsStrictPolicy() {
        val directory = policyDirectory()
        val policy = ArchitecturePolicyLoader.load(directory.toFile())

        assertEquals(setOf("core", "adapter"), policy.libraryModules)
        assertEquals(setOf(ModuleEdge("adapter", "core")), policy.edges)
        assertEquals("core", policy.splitPackages.getValue("sample.shared").owner)
    }

    @Test
    fun rejectsDuplicateRows() {
        val directory = policyDirectory()
        directory.resolve("edges.tsv").writeText(
            "consumer\tdependency\nadapter\tcore\nadapter\tcore\n",
        )

        val failure = assertFailsWith<IllegalArgumentException> {
            ArchitecturePolicyLoader.load(directory.toFile())
        }
        assertTrue(failure.message.orEmpty().contains("duplicate edge"))
    }

    @Test
    fun rejectsUnknownSplitContributor() {
        val directory = policyDirectory()
        directory.resolve("split-packages.tsv").writeText(
            "package\towner\tcontributors\nsample.shared\tcore\tcore,missing\n",
        )

        val failure = assertFailsWith<IllegalArgumentException> {
            ArchitecturePolicyLoader.load(directory.toFile())
        }
        assertTrue(failure.message.orEmpty().contains("unknown library contributor 'missing'"))
    }

    @Test
    fun rejectsDuplicateSplitContributor() {
        val directory = policyDirectory()
        directory.resolve("split-packages.tsv").writeText(
            "package\towner\tcontributors\nsample.shared\tcore\tcore,adapter,core\n",
        )

        val failure = assertFailsWith<IllegalArgumentException> {
            ArchitecturePolicyLoader.load(directory.toFile())
        }
        assertTrue(failure.message.orEmpty().contains("duplicate split-package contributor"))
    }

    private fun policyDirectory() = Files.createTempDirectory("architecture-policy").also { directory ->
        directory.resolve("modules.tsv").writeText(
            "module\tkind\tlayer\ncore\tlibrary\tL0\nadapter\tlibrary\tL6\ndemo\texample\t-\n",
        )
        directory.resolve("edges.tsv").writeText(
            "consumer\tdependency\nadapter\tcore\n",
        )
        directory.resolve("split-packages.tsv").writeText(
            "package\towner\tcontributors\nsample.shared\tcore\tcore,adapter\n",
        )
    }
}
