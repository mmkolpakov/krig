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

@Suppress("unused") // Policy and fragment rows are decoded through enumValues<T>().
internal enum class ProjectDependencyScope(val policyName: String) {
    Api("api"),
    Implementation("implementation"),
    CompileOnly("compileOnly"),
    RuntimeOnly("runtimeOnly"),
}

internal data class ProjectDependencyDeclaration(
    val consumer: String,
    val dependency: String,
    val sourceSet: String,
    val scope: ProjectDependencyScope,
) : Comparable<ProjectDependencyDeclaration> {
    val edge: ModuleEdge get() = ModuleEdge(consumer, dependency)

    override fun compareTo(other: ProjectDependencyDeclaration): Int = compareValuesBy(
        this,
        other,
        ProjectDependencyDeclaration::consumer,
        ProjectDependencyDeclaration::dependency,
        ProjectDependencyDeclaration::sourceSet,
        { declaration -> declaration.scope.policyName },
    )

    override fun toString(): String = "$consumer -> $dependency [$sourceSet/${scope.policyName}]"
}

internal data class PackagePolicy(
    val packageName: String,
    val owner: String,
    val contributors: Set<String>,
)

internal data class ArchitecturePolicy(
    val modules: Map<String, ModulePolicy>,
    val projectDependencies: Set<ProjectDependencyDeclaration>,
    val packages: Map<String, PackagePolicy>,
) {
    val libraryModules: Set<String> = modules.values
        .filter { it.kind == ModuleKind.Library }
        .mapTo(linkedSetOf()) { it.name }

    val edges: Set<ModuleEdge> = projectDependencies.mapTo(linkedSetOf(), ProjectDependencyDeclaration::edge)
}

internal data class ArchitectureSnapshot(
    val modules: Set<String>,
    val projectDependencies: Set<ProjectDependencyDeclaration>,
    val packageContributors: Map<String, Set<String>>,
) {
    val edges: Set<ModuleEdge> = projectDependencies.mapTo(linkedSetOf(), ProjectDependencyDeclaration::edge)
}

internal data class ArchitectureVerification(
    val errors: List<String>,
) {
    val isSuccessful: Boolean get() = errors.isEmpty()
}
