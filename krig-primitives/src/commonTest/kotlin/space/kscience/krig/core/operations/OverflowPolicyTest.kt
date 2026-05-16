package space.kscience.krig.core.operations

import kotlinx.coroutines.channels.BufferOverflow
import kotlin.test.Test
import kotlin.test.assertEquals

class OverflowPolicyTest {

    @Test
    fun overflowPolicySealedHierarchy() {
        val suspendPolicy: OverflowPolicy = OverflowPolicy.Suspend
        val drop: OverflowPolicy = OverflowPolicy.DropOldest
        val custom: OverflowPolicy = OverflowPolicy.Custom { /* no-op */ }

        val label = when (suspendPolicy) {
            is OverflowPolicy.Basic -> "basic-${suspendPolicy.kotlinx.name.lowercase()}"
            is OverflowPolicy.Custom -> "custom"
        }
        assertEquals("basic-suspend", label)
        assertEquals(OverflowPolicy.Basic(BufferOverflow.DROP_OLDEST), drop)
        val u = custom
    }
}
