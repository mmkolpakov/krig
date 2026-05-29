package space.kscience.magix.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import space.kscience.dataforge.names.parseAsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Fixture-based compatibility suite for the existing DataForge/Magix dialect. */
class MagixDialectFixturesTest {

    private val lenient = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

    @Test
    fun waltzDaqDialectEnvelopeRoundTrips() {
        val fixture = """
            {
              "format": "numass.daq",
              "payload": {
                "channel": 3,
                "value": 1.618,
                "unit": "eV",
                "timestamp": "2025-11-14T09:12:03.004Z"
              },
              "sourceEndpoint": "numass.spectrometer.ch3",
              "targetEndpoint": "hub.daq",
              "id": "daq-f91c",
              "user": { "name": "daq-service" }
            }
        """.trimIndent()

        val msg = lenient.decodeFromString(MagixMessage.serializer(), fixture)
        assertEquals("numass.daq", msg.format)
        assertEquals("numass.spectrometer.ch3".parseAsName(), msg.sourceEndpoint)
        assertEquals("daq-service", msg.user!!.jsonObject["name"]!!.jsonPrimitive.content)

        val payload = msg.payload.jsonObject
        assertEquals(3, payload["channel"]!!.jsonPrimitive.content.toInt())
        assertEquals("eV", payload["unit"]!!.jsonPrimitive.content)

        val reEncoded = lenient.encodeToString(MagixMessage.serializer(), msg)
        val tree = lenient.parseToJsonElement(reEncoded).jsonObject
        assertTrue("topic" !in tree)
        assertTrue("headers" !in tree)
    }

    @Test
    fun tangoJavaEeCommandRoundTrips() {
        val fixture = """
            {
              "format": "tango",
              "payload": {
                "op": "command",
                "device": "sys/tg_test/1",
                "command": "State",
                "argin": null,
                "argout": "RUNNING"
              },
              "sourceEndpoint": "tango.rest",
              "targetEndpoint": "sys/tg_test/1",
              "id": "a9d7-cmd-01",
              "parentId": "a9d7",
              "user": { "name": "operator", "roles": ["expert"] }
            }
        """.trimIndent()

        val msg = lenient.decodeFromString(MagixMessage.serializer(), fixture)
        assertEquals("tango", msg.format)
        assertEquals("a9d7", msg.parentId)
        assertEquals("tango.rest".parseAsName(), msg.sourceEndpoint)

        val payload = msg.payload.jsonObject
        assertEquals("command", payload["op"]!!.jsonPrimitive.content)
        assertEquals("State", payload["command"]!!.jsonPrimitive.content)
    }

    @Test
    fun magixMqttMinimalDialectEnvelopeRoundTrips() {
        val fixture = """
            {
              "format": "ping",
              "payload": "hello",
              "sourceEndpoint": "mqtt.bridge"
            }
        """.trimIndent()

        val msg = lenient.decodeFromString(MagixMessage.serializer(), fixture)
        assertEquals("ping", msg.format)
        assertEquals("mqtt.bridge".parseAsName(), msg.sourceEndpoint)
        assertNull(msg.id)
        assertNull(msg.user)
        assertNull(msg.targetEndpoint)
        assertNotNull(msg.payload)
    }

    @Test
    fun forwardCompatibleFieldsAreToleratedOnEveryFixture() {
        val fixture = """
            {
              "format": "numass.daq",
              "payload": { "v": 1 },
              "sourceEndpoint": "numass.spectrometer.ch3",
              "magixVersion": "2.0",
              "x-tango-correlation": "abc-123",
              "x-waltz-priority": 7
            }
        """.trimIndent()

        val msg = lenient.decodeFromString(MagixMessage.serializer(), fixture)
        assertEquals("numass.spectrometer.ch3".parseAsName(), msg.sourceEndpoint)
        assertTrue(msg.headers.isEmpty())
    }

    @Test
    fun envelopePayloadSurvivesStrictRelayHop() {
        val producerSide = MagixMessage(
            format = "krig",
            payload = lenient.parseToJsonElement(
                """{ "data": { "v": 42 }, "topic": "actions.motor.speed", "headers": { "krig.hlc": { "wall": 1, "logical": 0 } } }""",
            ),
            sourceEndpoint = "hub.a".parseAsName(),
            targetEndpoint = "hub.b".parseAsName(),
        )

        val wire = lenient.encodeToString(MagixMessage.serializer(), producerSide)
        val afterRelay = lenient.decodeFromString(MagixMessage.serializer(), wire)

        val envelope = afterRelay.payload.jsonObject
        assertEquals("actions.motor.speed", envelope["topic"]!!.jsonPrimitive.content)
        assertNotNull(envelope["headers"])
        assertEquals(42, (envelope["data"] as JsonObject)["v"]!!.jsonPrimitive.content.toInt())
    }
}
