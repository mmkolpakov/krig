package space.kscience.magix.api

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import space.kscience.dataforge.names.parseAsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertFailsWith

class WaltzRfc1AdapterTest {
    private val json = Json {
        encodeDefaults = false
        explicitNulls = false
        ignoreUnknownKeys = true
    }

    @Test
    fun canonicalRfc1MessageUsesOriginAndTarget() {
        val fixture = """
            {
              "origin": "hub.dev",
              "target": "numass.daq",
              "format": "numass",
              "payload": { "value": 42 },
              "id": "m-0001"
            }
        """.trimIndent()

        val decoded = json.decodeFromString(WaltzRfc1Message.serializer(), fixture)
        assertEquals("hub.dev".parseAsName(), decoded.origin)
        assertEquals("numass.daq".parseAsName(), decoded.target)

        val dialect = decoded.toMagixMessage()
        assertEquals(decoded.origin, dialect.sourceEndpoint)
        assertEquals(decoded.target, dialect.targetEndpoint)
        assertEquals("numass", dialect.format)
    }

    @Test
    fun dialectToCanonicalRfc1DoesNotLeakDialectKeys() {
        val dialect = MagixMessage(
            format = "numass",
            payload = buildJsonObject { put("value", JsonPrimitive(42)) },
            sourceEndpoint = "hub.dev".parseAsName(),
            targetEndpoint = "numass.daq".parseAsName(),
        )

        val encoded = json.encodeToString(WaltzRfc1Message.serializer(), dialect.toWaltzRfc1())
        val tree = json.parseToJsonElement(encoded).jsonObject

        assertEquals("hub.dev", tree["origin"]!!.jsonPrimitive.content)
        assertEquals("numass.daq", tree["target"]!!.jsonPrimitive.content)
        assertFalse("sourceEndpoint" in tree)
        assertFalse("targetEndpoint" in tree)
    }

    @Test
    fun krigMetadataIsWrappedIntoPayloadForRfc1Relays() {
        val dialect = MagixMessage(
            format = "krig",
            payload = buildJsonObject { put("value", JsonPrimitive(42)) },
            sourceEndpoint = "hub.a".parseAsName(),
            targetEndpoint = "hub.b".parseAsName(),
            topic = "actions.motor.speed".parseAsName(),
            headers = buildJsonObject { put("krig.hlc", JsonPrimitive("stamp-1")) },
        )

        val rfc = dialect.toWaltzRfc1()
        assertEquals(KRIG_ENVELOPE_FORMAT, rfc.format)
        val payload = rfc.payload!!.jsonObject
        assertEquals("krig", payload["format"]!!.jsonPrimitive.content)
        assertEquals("actions.motor.speed", payload["topic"]!!.jsonPrimitive.content)
        assertNotNull(payload["headers"])
        assertEquals(42, payload["data"]!!.jsonObject["value"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun wrappedRfc1MessageRestoresDialectMetadata() {
        val original = MagixMessage(
            format = "krig.controls",
            payload = buildJsonObject { put("value", JsonPrimitive(42)) },
            sourceEndpoint = "hub.a".parseAsName(),
            targetEndpoint = "hub.b".parseAsName(),
            topic = "actions.motor.speed".parseAsName(),
            headers = buildJsonObject { put("krig.hlc", JsonPrimitive("stamp-1")) },
            id = "m-1",
        )

        val restored = original.toWaltzRfc1().toMagixMessage()

        assertEquals(original.format, restored.format)
        assertEquals(original.payload, restored.payload)
        assertEquals(original.sourceEndpoint, restored.sourceEndpoint)
        assertEquals(original.targetEndpoint, restored.targetEndpoint)
        assertEquals(original.topic, restored.topic)
        assertEquals(original.headers, restored.headers)
        assertEquals(original.id, restored.id)
    }

    @Test
    fun controlsKtDialectIsNotCanonicalRfc1() {
        val fixture = """
            {
              "sourceEndpoint": "hub.dev",
              "targetEndpoint": "numass.daq",
              "format": "numass",
              "payload": { "value": 42 }
            }
        """.trimIndent()

        val dialect = json.decodeFromString(MagixMessage.serializer(), fixture)
        assertEquals("hub.dev".parseAsName(), dialect.sourceEndpoint)
        assertEquals("numass.daq".parseAsName(), dialect.targetEndpoint)

        assertFailsWith<SerializationException> {
            json.decodeFromString(WaltzRfc1Message.serializer(), fixture)
        }
    }
}
