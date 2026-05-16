package space.kscience.krig.api.identifiers

import space.kscience.krig.api.context.ExecutionContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CorrelationIdTest {

    @Test
    fun defaultExecutionContextUsesExplicitUnspecifiedSentinel() {
        val context = ExecutionContext()

        assertEquals(CorrelationId.Unspecified, context.correlationId)
        assertEquals("@unspecified", context.correlationId.id)
        assertFalse(context.correlationId.isSpecified)
    }

    @Test
    fun unspecifiedCorrelationIdMapsToNullOnWire() {
        assertNull(CorrelationId.Unspecified.wireValue)
        assertNull(CorrelationId.fromWire(null))
        assertNull(CorrelationId.fromWire(""))
        assertNull(CorrelationId.fromWire("@unspecified"))
    }

    @Test
    fun realCorrelationIdRoundTripsThroughWireValue() {
        val correlationId = CorrelationId("trace-42")

        assertTrue(correlationId.isSpecified)
        assertEquals("trace-42", correlationId.wireValue)
        assertEquals(correlationId, CorrelationId.fromWire(correlationId.wireValue))
    }
}
