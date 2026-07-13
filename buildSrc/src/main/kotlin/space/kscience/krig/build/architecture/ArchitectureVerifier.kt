package space.kscience.krig.build.architecture

internal object ArchitectureVerifier {
    fun verify(policy: ArchitecturePolicy, snapshot: ArchitectureSnapshot): ArchitectureVerification {
        val errors = mutableListOf<String>()

        val missingModules = policy.modules.keys - snapshot.modules
        val unexpectedModules = snapshot.modules - policy.modules.keys
        if (missingModules.isNotEmpty()) errors += "Missing modules: ${missingModules.sorted().joinToString()}"
        if (unexpectedModules.isNotEmpty()) errors += "Unclassified modules: ${unexpectedModules.sorted().joinToString()}"

        val missingEdges = policy.edges - snapshot.edges
        val unexpectedEdges = snapshot.edges - policy.edges
        if (missingEdges.isNotEmpty()) errors += "Missing project edges: ${missingEdges.sorted().joinToString()}"
        if (unexpectedEdges.isNotEmpty()) errors += "Unexpected project edges: ${unexpectedEdges.sorted().joinToString()}"

        snapshot.edges.sorted().forEach { edge ->
            val consumer = policy.modules[edge.consumer]
            val dependency = policy.modules[edge.dependency]
            if (consumer == null || dependency == null) return@forEach
            if (consumer.kind != ModuleKind.Library || dependency.kind != ModuleKind.Library) {
                errors += "Production edge must connect libraries: $edge"
                return@forEach
            }
            val consumerLayer = requireNotNull(consumer.layer)
            val dependencyLayer = requireNotNull(dependency.layer)
            if (dependencyLayer.ordinal > consumerLayer.ordinal) {
                errors += "Reverse layer edge $edge (${consumerLayer.name} -> ${dependencyLayer.name})"
            }
            if (consumerLayer == dependencyLayer && consumerLayer in independentLayers) {
                errors += if (consumerLayer == ArchitectureLayer.L3) {
                    "L3 ports must remain independent siblings: $edge"
                } else {
                    "${consumerLayer.name} modules must remain independent siblings: $edge"
                }
            }
        }

        findCycle(snapshot.edges, policy.libraryModules)?.let { cycle ->
            errors += "Production module cycle: ${cycle.joinToString(" -> ")}"
        }

        val actualSplits = snapshot.packageContributors
            .filterValues { it.size > 1 }
        val missingSplits = policy.splitPackages.keys - actualSplits.keys
        val unexpectedSplits = actualSplits.keys - policy.splitPackages.keys
        if (missingSplits.isNotEmpty()) {
            errors += "Stale split-package allowances: ${missingSplits.sorted().joinToString()}"
        }
        if (unexpectedSplits.isNotEmpty()) {
            errors += "Unapproved split packages: ${unexpectedSplits.sorted().joinToString()}"
        }
        (policy.splitPackages.keys intersect actualSplits.keys).sorted().forEach { packageName ->
            val expected = policy.splitPackages.getValue(packageName).contributors
            val actual = actualSplits.getValue(packageName)
            if (expected != actual) {
                errors += buildString {
                    append("Split package '").append(packageName).append("' contributors changed")
                    val missing = expected - actual
                    val added = actual - expected
                    if (missing.isNotEmpty()) append("; missing: ").append(missing.sorted().joinToString())
                    if (added.isNotEmpty()) append("; added: ").append(added.sorted().joinToString())
                }
            }
        }

        return ArchitectureVerification(errors.distinct().sorted())
    }

    private fun findCycle(edges: Set<ModuleEdge>, modules: Set<String>): List<String>? {
        val outgoing = modules.associateWith { mutableListOf<String>() }
        edges.forEach { edge ->
            if (edge.consumer in modules && edge.dependency in modules) {
                outgoing.getValue(edge.consumer) += edge.dependency
            }
        }
        outgoing.values.forEach { it.sort() }

        val visiting = linkedSetOf<String>()
        val visited = mutableSetOf<String>()

        fun visit(module: String): List<String>? {
            if (module in visiting) {
                val path = visiting.toList()
                val start = path.indexOf(module)
                return path.drop(start) + module
            }
            if (!visited.add(module)) return null
            visiting += module
            outgoing.getValue(module).forEach { dependency ->
                visit(dependency)?.let { return it }
            }
            visiting -= module
            return null
        }

        modules.sorted().forEach { module ->
            visit(module)?.let { return it }
        }
        return null
    }

    private val independentLayers: Set<ArchitectureLayer> = setOf(
        ArchitectureLayer.L0,
        ArchitectureLayer.L1,
        ArchitectureLayer.L3,
        ArchitectureLayer.L5,
    )
}
