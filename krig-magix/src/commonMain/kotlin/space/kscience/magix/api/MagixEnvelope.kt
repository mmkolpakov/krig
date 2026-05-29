package space.kscience.magix.api

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import space.kscience.dataforge.names.Name

/**
 * Interop-safe wrapper around a Magix payload.
 *
 * Waltz RFC1 has no message-level `topic` or `headers`. When traffic relays through non-krig
 * brokers (Tango-JavaEE, stock `magix-mqtt`, Waltz), routing hints and broker metadata belong
 * inside the payload so `ignoreUnknownKeys` on the relay cannot strip them.
 *
 * Wire shape:
 * ```json
 * { "topic": "motor.pv", "format": "krig.controls", "headers": { "krig.hlc": {...} }, "data": <payload> }
 * ```
 *
 * Envelope use is opt-in; when every hop is a krig broker the direct
 * [MagixMessage.topic] / [MagixMessage.headers] fields are cheaper.
 *
 * @property data The original, format-defined payload.
 * @property topic Routing key; matched against `MagixMessageFilter.topicPattern`.
 * @property headers Broker-level metadata (HLC stamps, trace IDs, causal breadcrumbs).
 * @property format Payload format before the message was wrapped for an interop hop.
 */
@Serializable
@SerialName("krig.envelope")
public data class MagixEnvelope<T>(
    val data: T,
    val topic: Name? = null,
    val headers: JsonObject = JsonObject(emptyMap()),
    val format: String? = null,
)

/** Wraps [payload] into an envelope with the given routing metadata. */
public fun <T> envelopeOf(
    payload: T,
    topic: Name? = null,
    headers: JsonObject = JsonObject(emptyMap()),
    format: String? = null,
): MagixEnvelope<T> = MagixEnvelope(data = payload, topic = topic, headers = headers, format = format)

/**
 * Encodes [payload] as a [JsonElement] matching the [MagixEnvelope] shape. Used by
 * publishers when outgoing traffic must survive interop hops.
 */
public fun <T> JsonElement.Companion.encodeEnvelope(
    json: Json,
    serializer: KSerializer<T>,
    payload: T,
    topic: Name? = null,
    headers: JsonObject = JsonObject(emptyMap()),
    format: String? = null,
): JsonElement = json.encodeToJsonElement(
    MagixEnvelope.serializer(serializer),
    MagixEnvelope(payload, topic, headers, format),
)

/**
 * Attempts to decode this [JsonElement] as a [MagixEnvelope]. Returns `null` when the
 * element is not envelope-shaped (i.e. a raw external payload).
 */
public fun <T> JsonElement.decodeEnvelopeOrNull(
    json: Json,
    serializer: KSerializer<T>,
): MagixEnvelope<T>? {
    val obj = this as? JsonObject ?: return null
    if ("data" !in obj) return null
    // Cheap shape check first: most external payloads do not have the envelope `data` key.
    // Payloads that do use `data` may still be raw, so the serializer remains the final
    // authority without forcing the exception path on every heterogeneous message.
    val topic = obj["topic"]
    if (topic != null && topic !== JsonNull && (topic !is JsonPrimitive || !topic.isString)) return null
    val headers = obj["headers"]
    if (headers != null && headers !is JsonObject) return null
    return try {
        json.decodeFromJsonElement(MagixEnvelope.serializer(serializer), this)
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}
