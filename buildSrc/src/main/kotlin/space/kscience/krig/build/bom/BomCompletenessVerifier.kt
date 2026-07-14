package space.kscience.krig.build.bom

internal object BomCompletenessVerifier {
    fun verify(policy: BomPolicy, snapshot: BomSnapshot): BomVerification {
        val errors = mutableListOf<String>()
        verifyIdentity(policy, snapshot.identity, errors)
        val expected = policy.expectedConstraints
        val expectedSet = expected.toSet()
        val actualCounts = snapshot.constraints.groupingBy { it }.eachCount().toSortedMap()

        expected.forEach { constraint ->
            when (val count = actualCounts[constraint] ?: 0) {
                0 -> errors += "Missing BOM constraint: ${constraint.diagnosticIdentity()}"
                1 -> Unit
                else -> errors += "Duplicate BOM constraint (${count} occurrences): ${constraint.diagnosticIdentity()}"
            }
        }

        actualCounts.forEach { (constraint, count) ->
            if (constraint !in expectedSet) {
                val occurrences = if (count == 1) "" else " ($count occurrences)"
                errors += "Unexpected BOM constraint$occurrences: ${constraint.diagnosticIdentity()}"
            }
        }

        return BomVerification(errors.distinct().sorted())
    }

    private fun verifyIdentity(policy: BomPolicy, identity: BomIdentity, errors: MutableList<String>) {
        if (identity.modelVersion != MAVEN_MODEL_VERSION) {
            errors += "BOM modelVersion is '${identity.modelVersion}', expected '$MAVEN_MODEL_VERSION'"
        }
        if (identity.group != policy.expectedBomCoordinate.group) {
            errors += "BOM group is '${identity.group}', expected '${policy.expectedBomCoordinate.group}'"
        }
        if (identity.artifact != policy.expectedBomCoordinate.artifact) {
            errors += "BOM artifact is '${identity.artifact}', expected '${policy.expectedBomCoordinate.artifact}'"
        }
        if (identity.version != policy.expectedVersion) {
            errors += "BOM version is '${identity.version}', expected '${policy.expectedVersion}'"
        }
        if (identity.packaging != MAVEN_POM_PACKAGING) {
            errors += "BOM packaging is '${identity.packaging}', expected '$MAVEN_POM_PACKAGING'"
        }
    }

    private fun BomConstraint.diagnosticIdentity(): String = buildString {
        append("$group:$artifact:$version")
        append(" [type=$type, classifier=${classifier ?: "<none>"}, scope=$scope, optional=$optional]")
    }
}
