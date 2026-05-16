package space.kscience.magix.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import space.kscience.dataforge.names.parseAsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Wire-level conformance tests against Waltz-Controls RFC1.
 *
 * RFC1 declares `sourceEndpoint` / `targetEndpoint` as plain strings and does not carry
 * `topic` or `headers` at the message level. These tests verify that:
 *
 * - RFC1-shaped JSON deserialises into [MagixMessage] losslessly.
 * - Names round-trip as primitive strings via dataforge's `NameSerializer`.
 * - Unknown upstream keys are accepted under `ignoreUnknownKeys`.
 * - Extra krig fields (`topic`, `headers`) round-trip locally without
 *   contaminating the RFC1 subset when defaults are stripped.
 */
class Rfc1RoundTripTest {

    private val strictJson = Json {
        encodeDefaults = false
        explicitNulls = false
    }

    private val lenientJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

    @Test
    fun upstreamRfc1MessageDeserialisesLosslessly() {
        // Shape captured from a magix-server relay: no topic, no headers, primitive endpoints.
        val upstream = """
            {
              "format": "numass",
              "payload": { "value": 42 },
              "sourceEndpoint": "hub.dev",
              "targetEndpoint": "numass.daq",
              "id": "m-0001",
              "parentId": null,
              "user": { "name": "alice" }
            }
        """.trimIndent()

        val decoded = strictJson.decodeFromString(MagixMessage.serializer(), upstream)

        assertEquals("numass", decoded.format)
        assertEquals("hub.dev".parseAsName(), decoded.sourceEndpoint)
        assertEquals("numass.daq".parseAsName(), decoded.targetEndpoint)
        assertEquals("m-0001", decoded.id)
        assertNull(decoded.parentId)
        assertNull(decoded.topic)
        assertTrue(decoded.headers.isEmpty())
        assertEquals("alice", decoded.user!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals(42, decoded.payload.jsonObject["value"]!!.jsonPrimitive.int)
    }

    @Test
    fun rfc1RoundTripProducesRfc1Shape() {
        val original = MagixMessage(
            format = "numass",
            payload = buildJsonObject { put("value", JsonPrimitive(42)) },
            sourceEndpoint = "hub.dev".parseAsName(),
            targetEndpoint = "numass.daq".parseAsName(),
            id = "m-0001",
            user = buildJsonObject { put("name", JsonPrimitive("alice")) },
        )

        val encoded = strictJson.encodeToString(MagixMessage.serializer(), original)
        val tree = strictJson.parseToJsonElement(encoded).jsonObject

        // Endpoints must be primitive strings, not objects — upstream clients depend on this.
        assertEquals("hub.dev", tree["sourceEndpoint"]!!.jsonPrimitive.content)
        assertEquals("numass.daq", tree["targetEndpoint"]!!.jsonPrimitive.content)

        // With encodeDefaults=false + explicitNulls=false, absent fields must not appear.
        assertTrue("topic" !in tree, "RFC1 payloads must not emit topic when absent")
        assertTrue("headers" !in tree, "RFC1 payloads must not emit empty headers")
        assertTrue("parentId" !in tree, "nulls must be omitted")

        // Round-trip preserves everything.
        val roundTripped = strictJson.decodeFromString(MagixMessage.serializer(), encoded)
        assertEquals(original, roundTripped)
    }

    @Test
    fun nameWithDotsSerialisesAsSingleString() {
        // Dotted name must survive a wire hop as a single primitive string, not a nested path.
        val message = MagixMessage(
            format = "numass",
            payload = JsonNull,
            sourceEndpoint = "numass.daq.spectrum".parseAsName(),
            targetEndpoint = "hub.dev".parseAsName(),
        )

        val encoded = strictJson.encodeToString(MagixMessage.serializer(), message)
        val tree = strictJson.parseToJsonElement(encoded).jsonObject
        assertEquals("numass.daq.spectrum", tree["sourceEndpoint"]!!.jsonPrimitive.content)

        val roundTripped = strictJson.decodeFromString(MagixMessage.serializer(), encoded)
        assertEquals(message.sourceEndpoint, roundTripped.sourceEndpoint)
    }

    @Test
    fun unknownKeysFromForwardCompatibleBrokersAreAccepted() {
        // Future RFC1 revisions or third-party extensions add fields we don't know yet.
        val withUnknown = """
            {
              "format": "numass",
              "payload": null,
              "sourceEndpoint": "hub.dev",
              "magixVersion": "2.0",
              "x-tango-correlation": "abc-123"
            }
        """.trimIndent()

        val decoded = lenientJson.decodeFromString(MagixMessage.serializer(), withUnknown)
        assertEquals("hub.dev".parseAsName(), decoded.sourceEndpoint)
    }

    @Test
    fun enrichedKrigMessageRoundTripsWithinHomoMesh() {
        // Homo-mesh traffic carries topic + headers directly on the message; these MUST
        // survive a local round-trip even though upstream relays would strip them.
        val message = MagixMessage(
            format = "krig",
            payload = JsonNull,
            sourceEndpoint = "hub.a".parseAsName(),
            topic = "actions.motor.speed".parseAsName(),
            headers = JsonObject(
                mapOf(
                    "krig.hlc" to JsonPrimitive("stamp-1"),
                    "krig.portal.seen" to JsonPrimitive("hub.a"),
                ),
            ),
        )

        val encoded = strictJson.encodeToString(MagixMessage.serializer(), message)
        val roundTripped = strictJson.decodeFromString(MagixMessage.serializer(), encoded)
        assertEquals(message, roundTripped)
        assertEquals("actions.motor.speed".parseAsName(), roundTripped.topic)
        assertNotNull(roundTripped.headers["krig.hlc"])
    }

    @Test
    fun envelopeDecoderRejectsInvalidRoutingShapeBeforeDeserializing() {
        val objectTopic = lenientJson.parseToJsonElement(
            """{ "data": { "value": 42 }, "topic": { "bad": true } }""",
        )
        val scalarHeaders = lenientJson.parseToJsonElement(
            """{ "data": { "value": 42 }, "headers": "bad" }""",
        )

        assertNull(objectTopic.decodeEnvelopeOrNull(lenientJson, JsonObject.serializer()))
        assertNull(scalarHeaders.decodeEnvelopeOrNull(lenientJson, JsonObject.serializer()))
    }
}
