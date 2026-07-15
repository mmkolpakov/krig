package space.kscience.krig.build.architecture

import kotlin.test.Test
import kotlin.test.assertTrue

class ArchitectureVerifierTest {
    @Test
    fun acceptsExactAcyclicBaseline() {
        val verification = ArchitectureVerifier.verify(
            policy = policy(),
            snapshot = snapshot(),
        )

        assertTrue(verification.isSuccessful, verification.errors.joinToString())
    }

    @Test
    fun rejectsUnknownModuleAndEdgeDelta() {
        val verification = ArchitectureVerifier.verify(
            policy = policy(),
            snapshot = snapshot(
                modules = setOf("core", "adapter", "unknown"),
                edges = emptySet(),
            ),
        )

        assertContains(verification, "Unclassified modules: unknown")
        assertContains(verification, "Missing project edges: adapter -> core")
    }

    @Test
    fun rejectsMissingModule() {
        val verification = ArchitectureVerifier.verify(
            policy = policy(),
            snapshot = snapshot(
                modules = setOf("core"),
                edges = emptySet(),
                packages = emptyMap(),
            ),
        )

        assertContains(verification, "Missing modules: adapter")
    }

    @Test
    fun rejectsUnexpectedEdge() {
        val unexpected = ModuleEdge("core", "adapter")
        val verification = ArchitectureVerifier.verify(
            policy = policy(),
            snapshot = snapshot(edges = setOf(ModuleEdge("adapter", "core"), unexpected)),
        )

        assertContains(verification, "Unexpected project edges: core -> adapter")
    }

    @Test
    fun rejectsReverseLayerEdge() {
        val modules = mapOf(
            "wire" to ModulePolicy("wire", ModuleKind.Library, ArchitectureLayer.L2),
            "adapter" to ModulePolicy("adapter", ModuleKind.Library, ArchitectureLayer.L6),
        )
        val edge = ModuleEdge("wire", "adapter")
        val verification = ArchitectureVerifier.verify(
            ArchitecturePolicy(modules, setOf(edge), emptyMap()),
            ArchitectureSnapshot(modules.keys, setOf(edge), emptyMap()),
        )

        assertContains(verification, "Reverse layer edge wire -> adapter (L2 -> L6)")
    }

    @Test
    fun rejectsCycleEvenWhenItIsInPolicy() {
        val modules = mapOf(
            "first" to ModulePolicy("first", ModuleKind.Library, ArchitectureLayer.L6),
            "second" to ModulePolicy("second", ModuleKind.Library, ArchitectureLayer.L6),
        )
        val edges = setOf(ModuleEdge("first", "second"), ModuleEdge("second", "first"))
        val verification = ArchitectureVerifier.verify(
            ArchitecturePolicy(modules, edges, emptyMap()),
            ArchitectureSnapshot(modules.keys, edges, emptyMap()),
        )

        assertContains(verification, "Production module cycle:")
    }

    @Test
    fun rejectsL3SiblingDependency() {
        val modules = mapOf(
            "contracts" to ModulePolicy("contracts", ModuleKind.Library, ArchitectureLayer.L3),
            "storage" to ModulePolicy("storage", ModuleKind.Library, ArchitectureLayer.L3),
        )
        val edge = ModuleEdge("contracts", "storage")
        val verification = ArchitectureVerifier.verify(
            ArchitecturePolicy(modules, setOf(edge), emptyMap()),
            ArchitectureSnapshot(modules.keys, setOf(edge), emptyMap()),
        )

        assertContains(verification, "L3 ports must remain independent siblings")
    }

    @Test
    fun rejectsUnclassifiedAndChangedPackages() {
        val verification = ArchitectureVerifier.verify(
            policy = policy(),
            snapshot = snapshot(
                packages = mapOf(
                    "sample.core" to setOf("core"),
                    "sample.shared" to setOf("core", "adapter", "third"),
                    "sample.new" to setOf("core", "adapter"),
                ),
            ),
        )

        assertContains(verification, "Unclassified production packages: sample.new")
        assertContains(verification, "Package 'sample.shared' contributors changed; added: third")
    }

    @Test
    fun rejectsRemovedPackageContributor() {
        val base = policy()
        val third = ModulePolicy("third", ModuleKind.Library, ArchitectureLayer.L6)
        val modules = base.modules + (third.name to third)
        val sharedPackage = base.packages.getValue("sample.shared").copy(
            contributors = setOf("core", "adapter", "third"),
        )
        val policy = base.copy(
            modules = modules,
            packages = mapOf(sharedPackage.packageName to sharedPackage),
        )
        val verification = ArchitectureVerifier.verify(
            policy = policy,
            snapshot = ArchitectureSnapshot(
                modules = modules.keys,
                edges = base.edges,
                packageContributors = mapOf(
                    "sample.core" to setOf("core"),
                    "sample.shared" to setOf("core", "adapter"),
                ),
            ),
        )

        assertContains(
            verification,
            "Package 'sample.shared' contributors changed; missing: third",
        )
    }

    @Test
    fun rejectsStalePackagePolicy() {
        val verification = ArchitectureVerifier.verify(
            policy = policy(),
            snapshot = snapshot(packages = mapOf("sample.core" to setOf("core"))),
        )

        assertContains(verification, "Stale package policies: sample.shared")
    }

    private fun policy(): ArchitecturePolicy {
        val modules = mapOf(
            "core" to ModulePolicy("core", ModuleKind.Library, ArchitectureLayer.L0),
            "adapter" to ModulePolicy("adapter", ModuleKind.Library, ArchitectureLayer.L6),
        )
        return ArchitecturePolicy(
            modules = modules,
            edges = setOf(ModuleEdge("adapter", "core")),
            packages = mapOf(
                "sample.core" to PackagePolicy(
                    packageName = "sample.core",
                    owner = "core",
                    contributors = setOf("core"),
                ),
                "sample.shared" to PackagePolicy(
                    packageName = "sample.shared",
                    owner = "core",
                    contributors = setOf("core", "adapter"),
                ),
            ),
        )
    }

    private fun snapshot(
        modules: Set<String> = setOf("core", "adapter"),
        edges: Set<ModuleEdge> = setOf(ModuleEdge("adapter", "core")),
        packages: Map<String, Set<String>> = mapOf(
            "sample.core" to setOf("core"),
            "sample.shared" to setOf("core", "adapter"),
        ),
    ): ArchitectureSnapshot = ArchitectureSnapshot(modules, edges, packages)

    private fun assertContains(verification: ArchitectureVerification, expected: String) {
        assertTrue(
            verification.errors.any { expected in it },
            "Expected '$expected' in ${verification.errors}",
        )
    }
}
