package space.kscience.krig.build.architecture

import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun derivesOneStructuralEdgeFromMultipleDeclarations() {
        val modules = mapOf(
            "core" to ModulePolicy("core", ModuleKind.Library, ArchitectureLayer.L0),
            "adapter" to ModulePolicy("adapter", ModuleKind.Library, ArchitectureLayer.L6),
        )
        val declarations = setOf(
            dependency("adapter", "core"),
            dependency("adapter", "core", sourceSet = "jvmMain", scope = ProjectDependencyScope.Implementation),
        )
        val policy = ArchitecturePolicy(modules, declarations, emptyMap())
        val snapshot = ArchitectureSnapshot(modules.keys, declarations, emptyMap())

        assertEquals(setOf(ModuleEdge("adapter", "core")), policy.edges)
        assertEquals(setOf(ModuleEdge("adapter", "core")), snapshot.edges)
        assertTrue(ArchitectureVerifier.verify(policy, snapshot).isSuccessful)
    }

    @Test
    fun rejectsUnknownModuleAndEdgeDelta() {
        val verification = ArchitectureVerifier.verify(
            policy = policy(),
            snapshot = snapshot(
                modules = setOf("core", "adapter", "unknown"),
                projectDependencies = emptySet(),
            ),
        )

        assertContains(verification, "Unclassified modules: unknown")
        assertContains(
            verification,
            "Missing project dependency declarations: adapter -> core [commonMain/api]",
        )
    }

    @Test
    fun rejectsMissingModule() {
        val verification = ArchitectureVerifier.verify(
            policy = policy(),
            snapshot = snapshot(
                modules = setOf("core"),
                projectDependencies = emptySet(),
                packages = emptyMap(),
            ),
        )

        assertContains(verification, "Missing modules: adapter")
    }

    @Test
    fun rejectsUnexpectedDependencyDeclaration() {
        val unexpected = dependency("core", "adapter")
        val verification = ArchitectureVerifier.verify(
            policy = policy(),
            snapshot = snapshot(projectDependencies = setOf(dependency("adapter", "core"), unexpected)),
        )

        assertContains(
            verification,
            "Unexpected project dependency declarations: core -> adapter [commonMain/api]",
        )
    }

    @Test
    fun rejectsReverseLayerEdge() {
        val modules = mapOf(
            "wire" to ModulePolicy("wire", ModuleKind.Library, ArchitectureLayer.L2),
            "adapter" to ModulePolicy("adapter", ModuleKind.Library, ArchitectureLayer.L6),
        )
        val declaration = dependency("wire", "adapter")
        val verification = ArchitectureVerifier.verify(
            ArchitecturePolicy(modules, setOf(declaration), emptyMap()),
            ArchitectureSnapshot(modules.keys, setOf(declaration), emptyMap()),
        )

        assertContains(verification, "Reverse layer edge wire -> adapter (L2 -> L6)")
    }

    @Test
    fun rejectsCycleEvenWhenItIsInPolicy() {
        val modules = mapOf(
            "first" to ModulePolicy("first", ModuleKind.Library, ArchitectureLayer.L6),
            "second" to ModulePolicy("second", ModuleKind.Library, ArchitectureLayer.L6),
        )
        val declarations = setOf(dependency("first", "second"), dependency("second", "first"))
        val verification = ArchitectureVerifier.verify(
            ArchitecturePolicy(modules, declarations, emptyMap()),
            ArchitectureSnapshot(modules.keys, declarations, emptyMap()),
        )

        assertContains(verification, "Production module cycle:")
    }

    @Test
    fun rejectsL3SiblingDependency() {
        val modules = mapOf(
            "contracts" to ModulePolicy("contracts", ModuleKind.Library, ArchitectureLayer.L3),
            "storage" to ModulePolicy("storage", ModuleKind.Library, ArchitectureLayer.L3),
        )
        val declaration = dependency("contracts", "storage")
        val verification = ArchitectureVerifier.verify(
            ArchitecturePolicy(modules, setOf(declaration), emptyMap()),
            ArchitectureSnapshot(modules.keys, setOf(declaration), emptyMap()),
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
                projectDependencies = base.projectDependencies,
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
            projectDependencies = setOf(dependency("adapter", "core")),
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
        projectDependencies: Set<ProjectDependencyDeclaration> = setOf(dependency("adapter", "core")),
        packages: Map<String, Set<String>> = mapOf(
            "sample.core" to setOf("core"),
            "sample.shared" to setOf("core", "adapter"),
        ),
    ): ArchitectureSnapshot = ArchitectureSnapshot(modules, projectDependencies, packages)

    private fun dependency(
        consumer: String,
        dependency: String,
        sourceSet: String = "commonMain",
        scope: ProjectDependencyScope = ProjectDependencyScope.Api,
    ): ProjectDependencyDeclaration = ProjectDependencyDeclaration(consumer, dependency, sourceSet, scope)

    private fun assertContains(verification: ArchitectureVerification, expected: String) {
        assertTrue(
            verification.errors.any { expected in it },
            "Expected '$expected' in ${verification.errors}",
        )
    }
}
