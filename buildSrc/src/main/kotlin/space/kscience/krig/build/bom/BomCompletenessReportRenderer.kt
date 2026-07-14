package space.kscience.krig.build.bom

internal object BomCompletenessReportRenderer {
    fun render(
        policy: BomPolicy,
        snapshot: BomSnapshot,
        verification: BomVerification,
    ): RenderedBomReport {
        val constraints = snapshot.constraints.sorted()
        val expectedPublications = policy.expectedPublications.sorted()
        val expectedConstraints = policy.expectedConstraints
        val presentExpectedConstraints = expectedConstraints.count { it in constraints }
        val errors = verification.errors.sorted()
        val status = if (errors.isEmpty()) "PASS" else "FAIL"

        return RenderedBomReport(
            json = buildString {
                appendLine("{")
                appendLine("  \"schemaVersion\": 4,")
                appendLine("  \"scope\": \"KRIG_BOM\",")
                appendLine("  \"coordinateScope\": \"$BOM_COORDINATE_SCOPE\",")
                appendLine("  \"status\": \"$status\",")
                appendLine("  \"evidenceLevel\": \"$BOM_EVIDENCE_LEVEL\",")
                appendLine("  \"expectedGroup\": ${jsonValue(policy.expectedBomCoordinate.group)},")
                appendLine("  \"expectedVersion\": ${jsonValue(policy.expectedVersion)},")
                appendLine("  \"expectedBomArtifact\": ${jsonValue(policy.expectedBomCoordinate.artifact)},")
                appendLine("  \"bomIdentity\": {")
                appendLine("    \"modelVersion\": ${jsonValue(snapshot.identity.modelVersion)},")
                appendLine("    \"group\": ${jsonValue(snapshot.identity.group)},")
                appendLine("    \"artifact\": ${jsonValue(snapshot.identity.artifact)},")
                appendLine("    \"version\": ${jsonValue(snapshot.identity.version)},")
                appendLine("    \"packaging\": ${jsonValue(snapshot.identity.packaging)}")
                appendLine("  },")
                appendLine("  \"counts\": {")
                appendLine("    \"expectedConstraints\": ${expectedConstraints.size},")
                appendLine("    \"managedConstraints\": ${constraints.size},")
                appendLine("    \"presentExpectedConstraints\": $presentExpectedConstraints,")
                appendLine("    \"violations\": ${errors.size}")
                appendLine("  },")
                appendLine("  \"expectedPublications\": [")
                expectedPublications.forEachIndexed { index, publication ->
                    appendLine(
                        "    {\"group\": ${jsonValue(publication.coordinate.group)}, " +
                            "\"artifact\": ${jsonValue(publication.coordinate.artifact)}, " +
                            "\"version\": ${jsonValue(policy.expectedVersion)}, " +
                            "\"kind\": ${jsonValue(publication.kind.name)}, " +
                            "\"type\": ${jsonValue(DEFAULT_MAVEN_TYPE)}, \"classifier\": null, " +
                            "\"scope\": ${jsonValue(DEFAULT_MAVEN_SCOPE)}, \"optional\": false}" +
                            comma(index, expectedPublications.lastIndex),
                    )
                }
                appendLine("  ],")
                appendLine("  \"actualConstraints\": [")
                constraints.forEachIndexed { index, constraint ->
                    appendConstraintJson(constraint, comma(index, constraints.lastIndex))
                }
                appendLine("  ],")
                appendLine("  \"errors\": [")
                errors.forEachIndexed { index, error ->
                    appendLine("    ${jsonValue(error)}${comma(index, errors.lastIndex)}")
                }
                appendLine("  ]")
                appendLine("}")
            },
            markdown = buildString {
                appendLine("# BOM completeness")
                appendLine()
                appendLine("Status: **$status**")
                appendLine()
                appendLine(
                    "Expected BOM coordinate: " +
                        "`${policy.expectedBomCoordinate}:${policy.expectedVersion}`",
                )
                appendLine()
                appendLine("Managed coordinate scope: `$BOM_COORDINATE_SCOPE`")
                appendLine()
                appendLine(
                    "Evidence level: `$BOM_EVIDENCE_LEVEL` (staged repository, Gradle Module Metadata, and " +
                        "consumer resolution are outside this task and remain required publication evidence).",
                )
                appendLine()
                appendLine("| Metric | Count |")
                appendLine("|---|---:|")
                appendLine("| Expected managed constraints | ${expectedConstraints.size} |")
                appendLine("| Managed constraints | ${constraints.size} |")
                appendLine("| Present exact expected constraints | $presentExpectedConstraints |")
                appendLine("| Violations | ${errors.size} |")
                appendLine()
                appendLine("## Expected public root publications")
                appendLine()
                appendLine("| Group | Artifact | Version | Kind |")
                appendLine("|---|---|---|---|")
                expectedPublications.forEach { publication ->
                    appendLine(
                        "| ${markdownValue(publication.coordinate.group)} | " +
                            "${markdownValue(publication.coordinate.artifact)} | " +
                            "${markdownValue(policy.expectedVersion)} | `${publication.kind.name}` |",
                    )
                }
                appendLine()
                appendLine("## Actual managed constraints")
                appendLine()
                appendLine("| Group | Artifact | Version | Type | Classifier | Scope | Optional |")
                appendLine("|---|---|---|---|---|---|---:|")
                constraints.forEach { constraint ->
                    appendLine(
                        "| ${markdownValue(constraint.group)} | ${markdownValue(constraint.artifact)} | " +
                            "${markdownValue(constraint.version)} | ${markdownValue(constraint.type)} | " +
                            "${constraint.classifier?.let(::markdownValue) ?: "—"} | " +
                            "${markdownValue(constraint.scope)} | ${constraint.optional} |",
                    )
                }
                if (errors.isNotEmpty()) {
                    appendLine()
                    appendLine("## Violations")
                    appendLine()
                    errors.forEach { appendLine("- ${markdownValue(it)}") }
                }
            },
        )
    }

    private fun StringBuilder.appendConstraintJson(constraint: BomConstraint, suffix: String) {
        appendLine(
            "    {\"group\": ${jsonValue(constraint.group)}, " +
                "\"artifact\": ${jsonValue(constraint.artifact)}, " +
                "\"version\": ${jsonValue(constraint.version)}, " +
                "\"type\": ${jsonValue(constraint.type)}, " +
                "\"classifier\": ${constraint.classifier?.let(::jsonValue) ?: "null"}, " +
                "\"scope\": ${jsonValue(constraint.scope)}, " +
                "\"optional\": ${constraint.optional}}$suffix",
        )
    }

    private fun comma(index: Int, lastIndex: Int): String = if (index == lastIndex) "" else ","

    private fun jsonValue(value: String): String = "\"${jsonEscape(value)}\""

    private fun jsonEscape(value: String): String = buildString(value.length) {
        value.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(char)
            }
        }
    }

    private fun markdownValue(value: String): String = value
        .replace("\\", "\\\\")
        .replace("|", "\\|")
        .replace("\r", " ")
        .replace("\n", " ")
}
