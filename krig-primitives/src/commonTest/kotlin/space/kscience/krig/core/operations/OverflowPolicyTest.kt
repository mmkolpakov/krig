package space.kscience.krig.core.operations

import kotlinx.coroutines.channels.BufferOverflow
import kotlin.test.Test
import kotlin.test.assertEquals

class OverflowPolicyTest {

    @Test
    fun overflowPolicySealedHierarchy() {
        val suspendPolicy: OverflowPolicy = OverflowPolicy.Suspend
        val drop: OverflowPolicy = OverflowPolicy.DropOldest
        val customPolicy: OverflowPolicy = OverflowPolicy.Custom { /* no-op */ }

        val label = when (suspendPolicy) {
            is OverflowPolicy.Basic -> "basic-${suspendPolicy.kotlinx.name.lowercase()}"
            is OverflowPolicy.Custom -> "custom"
        }
        val customLabel = when (customPolicy) {
            is OverflowPolicy.Basic -> "basic"
            is OverflowPolicy.Custom -> "custom"
        }
        assertEquals("basic-suspend", label)
        assertEquals("custom", customLabel)
        assertEquals(OverflowPolicy.Basic(BufferOverflow.DROP_OLDEST), drop)
    }
}
