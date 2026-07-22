package space.kscience.krig.ksp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GeneratedNamespaceTest {
    @Test
    fun acceptsOnlyRawKotlinSafePackageSegments() {
        assertEquals(
            "space.kscience.krig.generated.module_name.h0123456789abcdef",
            generatedNamespace("module_name.h0123456789abcdef").packageName,
        )
        for (invalid in listOf("when.module", "1module", "foo-bar", "foo..bar", "", "a".repeat(513))) {
            assertFailsWith<IllegalArgumentException>(invalid) { generatedNamespace(invalid) }
        }
    }
}
