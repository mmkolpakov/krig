package space.kscience.krig.api.messages

import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.asName
import space.kscience.krig.api.context.ExecutionContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/** The ingress bridge lifts the wire caller identity into [ExecutionContext] without losing the base. */
class RequestIdentityTest {

    @Test
    fun executionContextCarriesCallerIdentity() {
        val request = PropertyWriteRequest(
            time = Instant.fromEpochMilliseconds(0),
            property = "rpm".asName(),
            value = Meta.EMPTY,
            callerIdentity = "operator-7",
            sourceDevice = null,
            targetDevice = "pump".asName(),
        )

        val context = request.executionContext()

        assertEquals("operator-7", context.callerIdentity)
    }

    @Test
    fun bridgePreservesBaseAndOverlaysIdentity() {
        val base = ExecutionContext(originDevice = "gateway".asName())
        val request = PropertyReadRequest(
            time = Instant.fromEpochMilliseconds(0),
            property = "rpm".asName(),
            callerIdentity = "alice",
            sourceDevice = null,
            targetDevice = null,
        )

        val context = request.executionContext(base)

        assertEquals("alice", context.callerIdentity)
        assertEquals("gateway".asName(), context.originDevice)
    }
}
