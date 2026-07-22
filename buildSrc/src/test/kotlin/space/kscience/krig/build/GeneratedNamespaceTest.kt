package space.kscience.krig.build

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class GeneratedNamespaceTest {
    @Test
    fun namespaceIsStableKotlinSafeAndUsesPublicationCoordinates() {
        val baseline = generatedNamespace("space.kscience", "when", ".foo_bar.when")
        assertEquals(baseline, generatedNamespace("space.kscience", "when", ".moved.when"))
        assertTrue(
            baseline.value.split('.').all { it.matches(Regex("[a-z_][a-z0-9_]*")) },
            baseline.value,
        )
        assertTrue(baseline.value.startsWith("m_when."), baseline.value)

        assertNotEquals(
            generatedNamespace("space.kscience", "foo-bar", ".foo-bar").value,
            generatedNamespace("space.kscience", "foobar", ".foobar").value,
        )
        assertNotEquals(
            generatedNamespace("foo-bar", "module", ".module").value,
            generatedNamespace("foo.bar", "module", ".module").value,
        )
        assertTrue(
            generatedNamespace("space.kscience", "123-device", ".m_123-device")
                .value.startsWith("m_123_device."),
        )
    }

    @Test
    fun readableStemIsBoundedAndProjectMovesDoNotChangePublicNamespace() {
        val longArtifact = "Very-Long-Unicode-Физика-" + "device-".repeat(30)
        val shallow = generatedNamespace("space.kscience", longArtifact, ".module")
        val deep = generatedNamespace("space.kscience", longArtifact, ".a.b.c.module")

        assertEquals(shallow, deep)
        assertTrue(shallow.value.substringBefore('.').length <= 32, shallow.value)
        assertTrue(shallow.value.length <= 66, shallow.value)
        assertTrue(shallow.value.split('.').all { it.matches(Regex("[a-z_][a-z0-9_]*")) })
    }

    @Test
    fun rejectsMissingOrPathDerivedGradleGroup() {
        for (group in listOf("", "unspecified", ".module")) {
            val failure = assertFailsWith<IllegalArgumentException>(group) {
                generatedNamespace(group, "module", ".module")
            }
            assertTrue("requires an explicit stable project group" in failure.message.orEmpty())
        }
    }
}
