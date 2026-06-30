package space.kscience.magix.api

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName
import kotlin.test.Test
import kotlin.test.assertEquals

class MagixAuthorizationTest {

    private fun message(source: String): MagixMessage =
        MagixMessage(format = "krig", payload = JsonPrimitive(1.0), sourceEndpoint = source.asName())

    private class RecordingEndpoint(
        private val messages: List<MagixMessage>,
        private val applyBrokerFilter: Boolean = true,
    ) : MagixEndpoint {
        val filters: MutableList<MagixMessageFilter> = mutableListOf()

        override fun subscribe(filter: MagixMessageFilter): Flow<MagixMessage> {
            filters += filter
            val flow = flowOf(*messages.toTypedArray())
            return if (applyBrokerFilter) flow.filter(filter) else flow
        }

        override suspend fun broadcast(message: MagixMessage) = Unit
        override fun close() = Unit
    }

    private fun endpointOf(vararg messages: MagixMessage): RecordingEndpoint = RecordingEndpoint(messages.toList())

    @Test
    fun authorizedSubscribeDropsDeniedMessages() = runTest {
        val allowed: Name = "sensor".asName()
        val endpoint = endpointOf(message("sensor"), message("motor"), message("sensor"))

        val received = endpoint.authorizedSubscribe { it.sourceEndpoint == allowed }.toList()

        assertEquals(listOf(message("sensor"), message("sensor")), received)
    }

    @Test
    fun denyAllYieldsEmptyStream() = runTest {
        val endpoint = endpointOf(message("sensor"), message("motor"))

        val received = endpoint.authorizedSubscribe { false }.toList()

        assertEquals(emptyList(), received)
    }

    @Test
    fun authorizedSubscribeKeepsFilterLocalByDefault() = runTest {
        val sensor = message("sensor")
        val motor = message("motor")
        val endpoint = RecordingEndpoint(listOf(sensor, motor), applyBrokerFilter = false)
        val filter = MagixMessageFilter(source = setOf(sensor.sourceEndpoint))

        val received = endpoint.authorizedSubscribe(filter) { true }.toList()

        assertEquals(listOf(MagixMessageFilter.ALL), endpoint.filters)
        assertEquals(listOf(sensor), received)
    }

    @Test
    fun explicitPushdownStillAppliesLocalFilter() = runTest {
        val sensor = message("sensor")
        val motor = message("motor")
        val endpoint = RecordingEndpoint(listOf(sensor, motor), applyBrokerFilter = false)
        val filter = MagixMessageFilter(source = setOf(sensor.sourceEndpoint))
        val plan = AuthorizedMagixSubscriptionPlan(
            messageFilter = filter,
            pushdown = MagixSubscriptionPushdown.MessageFilter,
        )

        val received = endpoint.authorizedSubscribe(plan) { true }.toList()

        assertEquals(listOf(filter), endpoint.filters)
        assertEquals(listOf(sensor), received)
    }
}
