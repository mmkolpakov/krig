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

/**
 * Fixture-based compatibility suite against shapes observed on real Waltz-Controls deployments.
 *
 * Each fixture reproduces the payload silhouette of a known upstream producer:
 *
 * - **Waltz DAQ** — Numass spectrometer, pushes measurement envelopes through magix-server.
 * - **Tango-JavaEE** — `tango-rest-api` proxy relaying TangoCommand / TangoAttribute values.
 * - **magix-mqtt** — generic MQTT bridge, minimal RFC1 envelope, no `user`.
 *
 * A live end-to-end test against an actual `magix-server` is provided by a Magix server
 * integration module; this class guards the decoder layer alone, so regressions in the
 * base types fail fast without standing up a broker.
 */
class UpstreamRfc1FixturesTest {

    private val lenient = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

    @Test
    fun waltzDaqEnvelopeRoundTrips() {
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

        // Re-encoding must not introduce krig-only keys.
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
    fun magixMqttMinimalEnvelopeRoundTrips() {
        // magix-mqtt bridge emits the most stripped-down RFC1 shape: no id, no user, no parentId.
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
        // Any upstream adding vendor-prefixed fields (x-*, magixVersion, etc.) must not break
        // our decoder — RFC1 revisions are expected to extend, not replace.
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
    fun envelopePayloadSurvivesHeteroMeshHop() {
        // Simulates a krig producer pushing an envelope through an RFC1-only relay:
        // the relay strips top-level topic/headers but preserves payload verbatim.
        val producerSide = MagixMessage(
            format = "krig",
            payload = lenient.parseToJsonElement(
                """{ "data": { "v": 42 }, "topic": "actions.motor.speed", "headers": { "krig.hlc": { "wall": 1, "logical": 0 } } }""",
            ),
            sourceEndpoint = "hub.a".parseAsName(),
            targetEndpoint = "hub.b".parseAsName(),
        )

        val wire = lenient.encodeToString(MagixMessage.serializer(), producerSide)
        // Hetero relay drops unknown top-level keys; simulate by forcing ignoreUnknownKeys.
        val afterRelay = lenient.decodeFromString(MagixMessage.serializer(), wire)

        // The envelope payload must round-trip intact — that's the whole point of wrapping.
        val envelope = afterRelay.payload.jsonObject
        assertEquals("actions.motor.speed", envelope["topic"]!!.jsonPrimitive.content)
        assertNotNull(envelope["headers"])
        assertEquals(42, (envelope["data"] as JsonObject)["v"]!!.jsonPrimitive.content.toInt())
    }
}
