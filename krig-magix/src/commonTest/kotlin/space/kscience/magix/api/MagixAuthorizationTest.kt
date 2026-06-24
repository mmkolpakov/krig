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

    private fun endpointOf(vararg messages: MagixMessage): MagixEndpoint = object : MagixEndpoint {
        override fun subscribe(filter: MagixMessageFilter): Flow<MagixMessage> = flowOf(*messages).filter(filter)
        override suspend fun broadcast(message: MagixMessage) = Unit
        override fun close() = Unit
    }

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
}
