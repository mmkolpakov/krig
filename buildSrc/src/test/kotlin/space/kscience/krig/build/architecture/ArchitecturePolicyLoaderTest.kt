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
        assertEquals(
            setOf(
                ProjectDependencyDeclaration(
                    consumer = "adapter",
                    dependency = "core",
                    sourceSet = "commonMain",
                    scope = ProjectDependencyScope.Api,
                ),
            ),
            policy.projectDependencies,
        )
        assertEquals(setOf(ModuleEdge("adapter", "core")), policy.edges)
        assertEquals(setOf("core"), policy.packages.getValue("sample.core").contributors)
        assertEquals("core", policy.packages.getValue("sample.shared").owner)
    }

    @Test
    fun rejectsDuplicateRows() {
        val directory = policyDirectory()
        directory.resolve("dependencies.tsv").writeText(
            "consumer\tdependency\tsourceSet\tscope\n" +
                "adapter\tcore\tcommonMain\tapi\n" +
                "adapter\tcore\tcommonMain\tapi\n",
        )

        val failure = assertFailsWith<IllegalArgumentException> {
            ArchitecturePolicyLoader.load(directory.toFile())
        }
        assertTrue(failure.message.orEmpty().contains("duplicate project dependency declaration"))
    }

    @Test
    fun rejectsUnknownDependencyScope() {
        val directory = policyDirectory()
        directory.resolve("dependencies.tsv").writeText(
            "consumer\tdependency\tsourceSet\tscope\n" +
                "adapter\tcore\tcommonMain\tprovided\n",
        )

        val failure = assertFailsWith<IllegalArgumentException> {
            ArchitecturePolicyLoader.load(directory.toFile())
        }
        assertTrue(failure.message.orEmpty().contains("invalid dependency scope 'provided'"))
    }

    @Test
    fun acceptsKgpSourceSetNameThatIsNotAKotlinIdentifier() {
        val directory = policyDirectory()
        directory.resolve("dependencies.tsv").writeText(
            "consumer\tdependency\tsourceSet\tscope\n" +
                "adapter\tcore\tshared-main\tapi\n",
        )

        val policy = ArchitecturePolicyLoader.load(directory.toFile())

        assertEquals("shared-main", policy.projectDependencies.single().sourceSet)
    }

    @Test
    fun rejectsUnknownPackageContributor() {
        val directory = policyDirectory()
        directory.resolve("packages.tsv").writeText(
            "package\towner\tcontributors\nsample.shared\tcore\tcore,missing\n",
        )

        val failure = assertFailsWith<IllegalArgumentException> {
            ArchitecturePolicyLoader.load(directory.toFile())
        }
        assertTrue(failure.message.orEmpty().contains("unknown library contributor 'missing'"))
    }

    @Test
    fun rejectsDuplicatePackageContributor() {
        val directory = policyDirectory()
        directory.resolve("packages.tsv").writeText(
            "package\towner\tcontributors\nsample.shared\tcore\tcore,adapter,core\n",
        )

        val failure = assertFailsWith<IllegalArgumentException> {
            ArchitecturePolicyLoader.load(directory.toFile())
        }
        assertTrue(failure.message.orEmpty().contains("duplicate package contributor"))
    }

    @Test
    fun rejectsOwnerOutsideContributors() {
        val directory = policyDirectory()
        directory.resolve("packages.tsv").writeText(
            "package\towner\tcontributors\nsample.shared\tcore\tadapter\n",
        )

        val failure = assertFailsWith<IllegalArgumentException> {
            ArchitecturePolicyLoader.load(directory.toFile())
        }
        assertTrue(failure.message.orEmpty().contains("owner 'core' is not a contributor"))
    }

    private fun policyDirectory() = Files.createTempDirectory("architecture-policy").also { directory ->
        directory.resolve("modules.tsv").writeText(
            "module\tkind\tlayer\ncore\tlibrary\tL0\nadapter\tlibrary\tL6\ndemo\texample\t-\n",
        )
        directory.resolve("dependencies.tsv").writeText(
            "consumer\tdependency\tsourceSet\tscope\nadapter\tcore\tcommonMain\tapi\n",
        )
        directory.resolve("packages.tsv").writeText(
            "package\towner\tcontributors\n" +
                "sample.core\tcore\tcore\n" +
                "sample.shared\tcore\tcore,adapter\n",
        )
    }
}
