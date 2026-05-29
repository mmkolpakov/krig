package space.kscience.magix.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.parseAsName
import space.kscience.dataforge.names.toStringUnescaped

/** Canonical Waltz-Controls RFC1 JSON envelope. */
@Serializable
public data class WaltzRfc1Message(
    val origin: Name,
    val payload: JsonElement? = null,
    val format: String? = null,
    val target: Name? = null,
    val id: String? = null,
    val parentId: String? = null,
    val user: JsonElement? = null,
)

public const val KRIG_ENVELOPE_FORMAT: String = "krig.envelope"

/**
 * Converts KRig's Magix dialect envelope to canonical RFC1.
 *
 * When [wrapKrigMetadata] is true, KRig-only [MagixMessage.topic] and
 * [MagixMessage.headers] are moved into the payload so strict RFC1 relays keep them.
 */
public fun MagixMessage.toWaltzRfc1(
    wrapKrigMetadata: Boolean = topic != null || headers.isNotEmpty(),
    envelopeFormat: String = KRIG_ENVELOPE_FORMAT,
): WaltzRfc1Message {
    val rfcPayload = if (wrapKrigMetadata) {
        buildJsonObject {
            put("data", payload)
            put("format", JsonPrimitive(format))
            topic?.let { put("topic", JsonPrimitive(it.toStringUnescaped())) }
            if (headers.isNotEmpty()) put("headers", headers)
        }
    } else {
        payload
    }
    return WaltzRfc1Message(
        origin = sourceEndpoint,
        payload = rfcPayload,
        format = if (wrapKrigMetadata) envelopeFormat else format,
        target = targetEndpoint,
        id = id,
        parentId = parentId,
        user = user,
    )
}

/**
 * Converts a canonical RFC1 envelope into KRig's Magix/DataForge dialect.
 *
 * RFC1 does not require [format] or [payload]; callers may provide defaults that
 * match their bridge policy.
 */
public fun WaltzRfc1Message.toMagixMessage(
    defaultFormat: String = "magix",
    defaultPayload: JsonElement = JsonNull,
): MagixMessage {
    val envelope = payload as? JsonObject
    val isKrigEnvelope = format == KRIG_ENVELOPE_FORMAT && envelope != null && "data" in envelope
    val envelopeFormat = envelope?.stringOrNull("format")
    val envelopeTopic = envelope?.stringOrNull("topic")?.parseAsName()
    val envelopeHeaders = envelope?.get("headers") as? JsonObject

    return MagixMessage(
        format = if (isKrigEnvelope) envelopeFormat ?: defaultFormat else format ?: defaultFormat,
        payload = if (isKrigEnvelope) envelope["data"] ?: defaultPayload else payload ?: defaultPayload,
        sourceEndpoint = origin,
        targetEndpoint = target,
        topic = envelopeTopic?.takeIf { isKrigEnvelope && it != Name.EMPTY },
        id = id,
        parentId = parentId,
        user = user,
        headers = envelopeHeaders?.takeIf { isKrigEnvelope } ?: JsonObject(emptyMap()),
    )
}

private fun JsonObject.stringOrNull(key: String): String? =
    (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.jsonPrimitive?.content
