package space.kscience.krig.build.architecture

@Suppress("unused") // Policy rows are decoded through enumValues<T>().
internal enum class ModuleKind {
    Library,
    Build,
    Example,
    Benchmark,
    Platform,
}

@Suppress("unused") // Policy rows are decoded through enumValues<T>().
internal enum class ArchitectureLayer {
    L0,
    L1,
    L2,
    L3,
    L4,
    L5,
    L6,
}

internal data class ModulePolicy(
    val name: String,
    val kind: ModuleKind,
    val layer: ArchitectureLayer?,
)

internal data class ModuleEdge(
    val consumer: String,
    val dependency: String,
) : Comparable<ModuleEdge> {
    override fun compareTo(other: ModuleEdge): Int =
        compareValuesBy(this, other, ModuleEdge::consumer, ModuleEdge::dependency)

    override fun toString(): String = "$consumer -> $dependency"
}

internal data class PackagePolicy(
    val packageName: String,
    val owner: String,
    val contributors: Set<String>,
)

internal data class ArchitecturePolicy(
    val modules: Map<String, ModulePolicy>,
    val edges: Set<ModuleEdge>,
    val packages: Map<String, PackagePolicy>,
) {
    val libraryModules: Set<String> = modules.values
        .filter { it.kind == ModuleKind.Library }
        .mapTo(linkedSetOf()) { it.name }
}

internal data class ArchitectureSnapshot(
    val modules: Set<String>,
    val edges: Set<ModuleEdge>,
    val packageContributors: Map<String, Set<String>>,
)

internal data class ArchitectureVerification(
    val errors: List<String>,
) {
    val isSuccessful: Boolean get() = errors.isEmpty()
}
