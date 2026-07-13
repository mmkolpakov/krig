package space.kscience.krig.assembly

import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.HexFormat
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ProcessFlowFixtureResourceTest {

    private val classLoader: ClassLoader = javaClass.classLoader

    @Test
    fun chemicalFactoryResourceIsLoadable() {
        val resource = classLoader.getResource(FIXTURE_PATH)

        assertNotNull(resource)
        val model = ExternalFlowModelDocument.fromJsonString(resource.readText())
        val diagnostics = model.validateProcessFlowDialect()

        assertTrue(diagnostics.none { it.severity == ProcessFlowDiagnosticSeverity.Error }, diagnostics.toString())
    }

    @Test
    fun chemicalFactoryResourceHasVerifiedProvenance() {
        val fixture = classLoader.resourceBytes(FIXTURE_PATH)
        val provenance = Properties().apply {
            load(ByteArrayInputStream(classLoader.resourceBytes(PROVENANCE_PATH)))
        }

        assertEquals(REQUIRED_PROVENANCE_KEYS, provenance.stringPropertyNames())
        assertEquals("Apache-2.0", provenance.getProperty("license"))
        assertEquals("git-blob-copy", provenance.getProperty("transform"))
        assertTrue(provenance.getProperty("sourceRepository").startsWith("https://"))
        assertTrue(provenance.getProperty("sourceRevision").isFullGitObjectId())
        assertTrue(provenance.getProperty("sourceGitBlob").isFullGitObjectId())
        assertTrue(provenance.getProperty("sourceIntroductionRevision").isFullGitObjectId())
        assertEquals(provenance.getProperty("contentSha256"), fixture.sha256())
    }

    private fun ClassLoader.resourceBytes(path: String): ByteArray {
        val resource = getResource(path)
        assertNotNull(resource, "Missing test resource: $path")
        return resource.readBytes()
    }

    private fun String.isFullGitObjectId(): Boolean =
        length == GIT_OBJECT_ID_LENGTH && all { it in '0'..'9' || it in 'a'..'f' }

    private fun ByteArray.sha256(): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(this))

    private companion object {
        const val FIXTURE_PATH: String = "process-flow/ChemicalFactory.json"
        const val PROVENANCE_PATH: String = "process-flow/ChemicalFactory.provenance.properties"
        const val GIT_OBJECT_ID_LENGTH: Int = 40

        val REQUIRED_PROVENANCE_KEYS: Set<String> = setOf(
            "sourceRepository",
            "sourceRevision",
            "sourcePath",
            "sourceGitBlob",
            "sourceIntroductionRevision",
            "license",
            "transform",
            "contentSha256",
        )
    }
}
