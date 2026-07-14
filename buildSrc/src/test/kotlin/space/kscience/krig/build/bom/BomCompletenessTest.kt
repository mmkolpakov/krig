package space.kscience.krig.build.bom

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import space.kscience.krig.build.publication.PublicationCoordinate
import space.kscience.krig.build.publication.PublicationKind

class BomCompletenessTest {
    @Test
    fun parserAndReportsAreDeterministicForReorderedConstraints() {
        val state = BomConstraint(GROUP, "krig-state", VERSION)
        val model = BomConstraint(GROUP, "krig-model", VERSION)
        val first = parsePom(pom(state, model))
        val second = parsePom(pom(model, state))
        val policy = policy(
            "krig-state" to PublicationKind.KMP_ROOT,
            "krig-model" to PublicationKind.JVM_DIRECT,
        )

        assertEquals(first, second)
        val firstReport = BomCompletenessReportRenderer.render(
            policy,
            first,
            BomCompletenessVerifier.verify(policy, first),
        )
        val secondReport = BomCompletenessReportRenderer.render(
            policy,
            second,
            BomCompletenessVerifier.verify(policy, second),
        )

        assertEquals(firstReport, secondReport)
        assertEquals(
            listOf(
                "{",
                "  \"schemaVersion\": 4,",
                "  \"scope\": \"KRIG_BOM\",",
                "  \"coordinateScope\": \"PUBLIC_ROOT\",",
                "  \"status\": \"PASS\",",
            ),
            firstReport.json.lineSequence().take(5).toList(),
        )
        assertTrue("\"actualConstraints\"" in firstReport.json)
        assertTrue("\"evidenceLevel\": \"GENERATED_POM_ONLY\"" in firstReport.json)
        assertTrue("\"kind\": \"KMP_ROOT\"" in firstReport.json)
        assertTrue("\"kind\": \"JVM_DIRECT\"" in firstReport.json)
        assertTrue("\"group\": \"$GROUP\"" in firstReport.json)
        assertTrue("\"type\": \"jar\"" in firstReport.json)
        assertTrue("\"classifier\": null" in firstReport.json)
        assertTrue("\"scope\": \"compile\"" in firstReport.json)
        assertTrue("\"optional\": false" in firstReport.json)
        assertTrue("Present exact expected constraints | 2" in firstReport.markdown)
    }

    @Test
    fun rejectsDoctypeBeforeAnExternalEntityCanBeExpanded() {
        val secret = Files.createTempFile("krig-bom-xxe-", ".txt")
        try {
            Files.writeString(secret, "must-not-be-read", StandardCharsets.UTF_8)
            val xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE project [<!ENTITY xxe SYSTEM="${secret.toUri()}">]>
                <project xmlns="$MAVEN_POM_NAMESPACE">
                  <dependencyManagement><dependencies><dependency>
                    <groupId>&xxe;</groupId><artifactId>krig-state</artifactId><version>$VERSION</version>
                  </dependency></dependencies></dependencyManagement>
                </project>
            """.trimIndent()

            val failure = assertFailsWith<IllegalArgumentException> { parsePom(xml) }

            assertEquals("Maven POM must not contain a DOCTYPE declaration", failure.message)
        } finally {
            Files.deleteIfExists(secret)
        }
    }

    @Test
    fun reportsMissingExpectedConstraint() {
        val verification = verify(policy("krig-state", "krig-model"), BomConstraint(GROUP, "krig-state", VERSION))

        assertContains(verification, "Missing BOM constraint: ${expectedIdentity("krig-model")}")
    }

    @Test
    fun rejectsEveryNonManifestManagedDependency() {
        val external = BomConstraint("org.jetbrains.kotlinx", "kotlinx-coroutines-core", "1.10.2")
        val verification = verifyParsed(
            policy("krig-state"),
            BomConstraint(GROUP, "krig-state", VERSION),
            external,
        )

        assertEquals(
            listOf("Unexpected BOM constraint: ${diagnosticIdentity(external)}"),
            verification.errors,
        )
    }

    @Test
    fun doesNotMatchAnExpectedArtifactByNameAcrossGroups() {
        val shadow = BomConstraint("other.group", "krig-state", VERSION)
        val verification = verifyParsed(policy("krig-state"), shadow)

        assertEquals(
            listOf(
                "Missing BOM constraint: ${expectedIdentity("krig-state")}",
                "Unexpected BOM constraint: ${diagnosticIdentity(shadow)}",
            ).sorted(),
            verification.errors,
        )
    }

    @Test
    fun doesNotGrantAuthorityFromTheExpectedGroupOrArtifactPrefix() {
        val extra = BomConstraint(GROUP, "krig-extra", VERSION)
        val verification = verifyParsed(
            policy("krig-state"),
            BomConstraint(GROUP, "krig-state", VERSION),
            extra,
        )

        assertEquals(
            listOf("Unexpected BOM constraint: ${diagnosticIdentity(extra)}"),
            verification.errors,
        )
    }

    @Test
    fun reportsDuplicateExactConstraintDeterministically() {
        val state = BomConstraint(GROUP, "krig-state", VERSION)
        val verification = verifyParsed(policy("krig-state"), state, state)

        assertEquals(
            listOf("Duplicate BOM constraint (2 occurrences): ${diagnosticIdentity(state)}"),
            verification.errors,
        )
    }

    @Test
    fun comparesEveryMavenConstraintDimensionExactly() {
        val expected = BomConstraint(GROUP, "krig-state", VERSION)
        val mutations = listOf(
            expected.copy(version = "9.9.9"),
            expected.copy(type = "pom"),
            expected.copy(classifier = "tests"),
            expected.copy(scope = "import"),
            expected.copy(optional = true),
        )

        mutations.forEach { mutation ->
            val verification = verifyParsed(policy("krig-state"), mutation)
            assertContains(verification, "Missing BOM constraint: ${diagnosticIdentity(expected)}")
            assertContains(verification, "Unexpected BOM constraint: ${diagnosticIdentity(mutation)}")
            assertEquals(2, verification.errors.size, mutation.toString())
        }
    }

    @Test
    fun acceptsAnExactTwentyCoordinateSurfaceWithoutNameHeuristics() {
        val publications = (1..20).map { index ->
            BomExpectedPublication(
                coordinate = PublicationCoordinate("example.group$index", "library$index"),
                kind = if (index % 2 == 0) PublicationKind.KMP_ROOT else PublicationKind.JVM_DIRECT,
            )
        }
        val policy = BomPolicy(
            expectedBomCoordinate = PublicationCoordinate(GROUP, "krig-bom"),
            expectedVersion = VERSION,
            expectedPublications = publications,
        )
        val snapshot = BomSnapshot(identity(), policy.expectedConstraints.reversed())

        assertTrue(BomCompletenessVerifier.verify(policy, snapshot).isSuccessful)
    }

    @Test
    fun rejectsDuplicateExpectedCoordinates() {
        val coordinate = PublicationCoordinate(GROUP, "krig-state")

        val failure = assertFailsWith<IllegalArgumentException> {
            BomPolicy(
                expectedBomCoordinate = PublicationCoordinate(GROUP, "krig-bom"),
                expectedVersion = VERSION,
                expectedPublications = listOf(
                    BomExpectedPublication(coordinate, PublicationKind.KMP_ROOT),
                    BomExpectedPublication(coordinate, PublicationKind.JVM_DIRECT),
                ),
            )
        }

        assertEquals("Expected BOM publication coordinates must be unique", failure.message)
    }

    @Test
    fun reportsEveryBomIdentityMismatch() {
        val xml = pom(BomConstraint(GROUP, "krig-state", VERSION))
            .replaceFirst("<modelVersion>$MAVEN_MODEL_VERSION</modelVersion>", "<modelVersion>3.0.0</modelVersion>")
            .replaceFirst("<groupId>$GROUP</groupId>", "<groupId>wrong.group</groupId>")
            .replaceFirst("<artifactId>krig-bom</artifactId>", "<artifactId>wrong-bom</artifactId>")
            .replaceFirst("<version>$VERSION</version>", "<version>9.9.9</version>")
            .replaceFirst("<packaging>$MAVEN_POM_PACKAGING</packaging>", "<packaging>jar</packaging>")
        val verification = BomCompletenessVerifier.verify(policy("krig-state"), parsePom(xml))

        assertContains(verification, "BOM modelVersion is '3.0.0', expected '4.0.0'")
        assertContains(verification, "BOM group is 'wrong.group', expected '$GROUP'")
        assertContains(verification, "BOM artifact is 'wrong-bom', expected 'krig-bom'")
        assertContains(verification, "BOM version is '9.9.9', expected '$VERSION'")
        assertContains(verification, "BOM packaging is 'jar', expected 'pom'")
    }

    @Test
    fun rejectsForeignPomNamespace() {
        val xml = pom(BomConstraint(GROUP, "krig-state", VERSION))
            .replace(MAVEN_POM_NAMESPACE, "urn:not-maven")

        val failure = assertFailsWith<IllegalArgumentException> { parsePom(xml) }

        assertEquals(
            "Maven POM root element must use namespace '$MAVEN_POM_NAMESPACE'",
            failure.message,
        )
    }

    @Test
    fun rejectsUnsupportedAndSemanticDependencyChildren() {
        val mutations = listOf(
            "systemPath" to "<systemPath>/tmp/library.jar</systemPath>",
            "exclusions" to (
                "<exclusions><exclusion><groupId>x</groupId>" +
                    "<artifactId>y</artifactId></exclusion></exclusions>"
            ),
            "futureField" to "<futureField>value</futureField>",
        )

        mutations.forEach { (name, child) ->
            val xml = pom(BomConstraint(GROUP, "krig-state", VERSION)).replaceFirst(
                "      </dependency>",
                "        $child\n      </dependency>",
            )
            val failure = assertFailsWith<IllegalArgumentException>(name) { parsePom(xml) }
            assertEquals("Unsupported Maven <dependency> child <$name>", failure.message)
        }
    }

    @Test
    fun rejectsNestedElementsInsideScalarDependencyFields() {
        val xml = pom(BomConstraint(GROUP, "krig-state", VERSION)).replaceFirst(
            "<artifactId>krig-state</artifactId>",
            "<artifactId><value>krig-state</value></artifactId>",
        )

        val failure = assertFailsWith<IllegalArgumentException> { parsePom(xml) }

        assertEquals("Maven <artifactId> must contain text only", failure.message)
    }

    @Test
    fun rejectsUnknownDependencyManagementStructure() {
        val xml = pom(BomConstraint(GROUP, "krig-state", VERSION)).replaceFirst(
            "  <dependencyManagement>",
            "  <dependencyManagement>\n    <imports/>",
        )

        val failure = assertFailsWith<IllegalArgumentException> { parsePom(xml) }

        assertEquals("Unsupported Maven <dependencyManagement> child <imports>", failure.message)
    }

    @Test
    fun rejectsUnknownDependenciesContainerStructure() {
        val xml = pom(BomConstraint(GROUP, "krig-state", VERSION)).replaceFirst(
            "    <dependencies>",
            "    <dependencies>\n      <constraint/>",
        )

        val failure = assertFailsWith<IllegalArgumentException> { parsePom(xml) }

        assertEquals("Unsupported Maven <dependencies> child <constraint>", failure.message)
    }

    @Test
    fun rejectsDuplicateKnownDependencyChild() {
        val xml = pom(BomConstraint(GROUP, "krig-state", VERSION)).replaceFirst(
            "<artifactId>krig-state</artifactId>",
            "<artifactId>krig-state</artifactId><artifactId>shadow</artifactId>",
        )

        val failure = assertFailsWith<IllegalArgumentException> { parsePom(xml) }

        assertEquals("Maven <dependency> must contain exactly one <artifactId> element", failure.message)
    }

    @Test
    fun parserReadsOnlyDependencyManagement() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="$MAVEN_POM_NAMESPACE">
              <modelVersion>4.0.0</modelVersion>
              <groupId>$GROUP</groupId>
              <artifactId>krig-bom</artifactId>
              <version>$VERSION</version>
              <packaging>pom</packaging>
              <dependencies>
                <dependency>
                  <groupId>$GROUP</groupId><artifactId>krig-runtime-only</artifactId><version>$VERSION</version>
                </dependency>
              </dependencies>
              <dependencyManagement><dependencies>
                <dependency>
                  <groupId>$GROUP</groupId><artifactId>krig-state</artifactId><version>$VERSION</version>
                </dependency>
              </dependencies></dependencyManagement>
            </project>
        """.trimIndent()

        assertEquals(
            BomSnapshot(identity(), listOf(BomConstraint(GROUP, "krig-state", VERSION))),
            parsePom(xml),
        )
    }

    private fun verify(policy: BomPolicy, vararg constraints: BomConstraint): BomVerification =
        BomCompletenessVerifier.verify(policy, BomSnapshot(identity(), constraints.toList()))

    private fun verifyParsed(policy: BomPolicy, vararg constraints: BomConstraint): BomVerification =
        BomCompletenessVerifier.verify(policy, parsePom(pom(*constraints)))

    private fun policy(vararg artifacts: String): BomPolicy = policy(
        *artifacts.map { it to PublicationKind.KMP_ROOT }.toTypedArray(),
    )

    private fun policy(vararg publications: Pair<String, PublicationKind>): BomPolicy = BomPolicy(
        expectedBomCoordinate = PublicationCoordinate(GROUP, "krig-bom"),
        expectedVersion = VERSION,
        expectedPublications = publications.map { (artifact, kind) ->
            BomExpectedPublication(PublicationCoordinate(GROUP, artifact), kind)
        },
    )

    private fun identity(): BomIdentity = BomIdentity(
        modelVersion = MAVEN_MODEL_VERSION,
        group = GROUP,
        artifact = "krig-bom",
        version = VERSION,
        packaging = MAVEN_POM_PACKAGING,
    )

    private fun parsePom(xml: String): BomSnapshot = MavenBomParser.parse(
        ByteArrayInputStream(xml.toByteArray(StandardCharsets.UTF_8)),
    )

    private fun pom(vararg constraints: BomConstraint): String = buildString {
        appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
        appendLine("<project xmlns=\"$MAVEN_POM_NAMESPACE\">")
        appendLine("  <modelVersion>$MAVEN_MODEL_VERSION</modelVersion>")
        appendLine("  <groupId>$GROUP</groupId>")
        appendLine("  <artifactId>krig-bom</artifactId>")
        appendLine("  <version>$VERSION</version>")
        appendLine("  <packaging>$MAVEN_POM_PACKAGING</packaging>")
        appendLine("  <dependencyManagement>")
        appendLine("    <dependencies>")
        constraints.forEach { constraint ->
            appendLine("      <dependency>")
            appendLine("        <groupId>${constraint.group}</groupId>")
            appendLine("        <artifactId>${constraint.artifact}</artifactId>")
            appendLine("        <version>${constraint.version}</version>")
            if (constraint.type != DEFAULT_MAVEN_TYPE) appendLine("        <type>${constraint.type}</type>")
            constraint.classifier?.let { appendLine("        <classifier>$it</classifier>") }
            if (constraint.scope != DEFAULT_MAVEN_SCOPE) appendLine("        <scope>${constraint.scope}</scope>")
            if (constraint.optional) appendLine("        <optional>true</optional>")
            appendLine("      </dependency>")
        }
        appendLine("    </dependencies>")
        appendLine("  </dependencyManagement>")
        appendLine("</project>")
    }

    private fun expectedIdentity(artifact: String): String = diagnosticIdentity(BomConstraint(GROUP, artifact, VERSION))

    private fun diagnosticIdentity(constraint: BomConstraint): String = with(constraint) {
        "$group:$artifact:$version [type=$type, classifier=${classifier ?: "<none>"}, scope=$scope, optional=$optional]"
    }

    private fun assertContains(verification: BomVerification, expected: String) {
        assertTrue(expected in verification.errors, "Expected '$expected' in ${verification.errors}")
    }

    private companion object {
        const val GROUP = "space.kscience"
        const val VERSION = "1.0.0-alpha-3"
    }
}
