package space.kscience.krig.core.dataforge

import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.json.Json
import space.kscience.dataforge.io.Binary
import space.kscience.dataforge.io.Envelope
import space.kscience.dataforge.io.asBinary
import space.kscience.dataforge.io.toByteArray
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.long
import space.kscience.dataforge.meta.string
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.parseAsName
import space.kscience.krig.api.data.HlcNodeId
import space.kscience.krig.api.data.HlcTimestamp
import space.kscience.krig.api.identifiers.CorrelationId
import space.kscience.krig.api.identifiers.wireValue
import space.kscience.krig.api.messages.DeviceMessage
import space.kscience.krig.api.messages.DeviceMessageFrame
import space.kscience.krig.api.messages.KrigWireHeaders
import space.kscience.krig.api.messages.MessageContext
import space.kscience.krig.api.serialization.krigStorageJson

/**
 * Canonical wire-key dictionary for lowering a [DeviceMessageFrame] into a DataForge
 * `Envelope`. Any transport bridge (Magix headers, file/stream storage) should reuse these
 * keys so HLC/correlation metadata travels under one vocabulary instead of ad-hoc headers.
 *
 * This is a flat set of [Name] keys, not a DataForge `Scheme`/`MetaScheme`: it documents the
 * envelope-meta contract rather than projecting a typed view over mutable [Meta].
 */
public object DeviceMessageFrameKeys {
    public val MESSAGE_TYPE: Name = "krig.message.type".parseAsName()
    public val CORRELATION_ID: Name = "krig.message.correlationId".parseAsName()
    public val VERIFIED_IDENTITY: Name = "krig.message.verifiedIdentity".parseAsName()
    public val HLC_PHYSICAL_MS: Name = "krig.message.hlc.physicalMs".parseAsName()
    public val HLC_LOGICAL: Name = "krig.message.hlc.logical".parseAsName()
    public val HLC_NODE: Name = "krig.message.hlc.node".parseAsName()
    public val ATTRIBUTES: Name = "krig.message.attributes".parseAsName()
    public const val ENVELOPE_TYPE: String = "krig.device-message"
    public const val JSON_DATA_TYPE: String = "application/vnd.krig.device-message+json"
}

/** Lowers a typed [DeviceMessageFrame] to/from a schemaless DataForge `Envelope`. */
public interface DeviceMessageFrameCodec {
    public fun encode(frame: DeviceMessageFrame<DeviceMessage>): Envelope
    public fun decode(envelope: Envelope): DeviceMessageFrame<DeviceMessage>
}

public class KotlinxJsonDeviceMessageFrameCodec(
    private val json: Json = krigStorageJson(),
) : DeviceMessageFrameCodec {
    private val serializer = PolymorphicSerializer(DeviceMessage::class)

    override fun encode(frame: DeviceMessageFrame<DeviceMessage>): Envelope {
        val payload = json.encodeToString(serializer, frame.payload).encodeToByteArray().asBinary()
        return Envelope(frame.context.toEnvelopeMeta(frame.payload), payload)
    }

    override fun decode(envelope: Envelope): DeviceMessageFrame<DeviceMessage> {
        val payloadBinary = envelope.data ?: Binary.EMPTY
        val payloadText = payloadBinary.toByteArray().decodeToString()
        return DeviceMessageFrame(
            payload = json.decodeFromString(serializer, payloadText),
            context = envelope.meta.toMessageContext(),
        )
    }
}

/** Extracts stable KRig broker headers from a DataForge [Envelope] produced by [DeviceMessageFrameCodec]. */
public fun Envelope.toKrigWireHeaders(): Map<String, String> = buildMap {
    meta[DeviceMessageFrameKeys.MESSAGE_TYPE]?.string?.let { put(KrigWireHeaders.MessageType, it) }
    meta[DeviceMessageFrameKeys.CORRELATION_ID]?.string?.let { put(KrigWireHeaders.CorrelationId, it) }
}

private fun MessageContext.toEnvelopeMeta(message: DeviceMessage): Meta = Meta {
    DeviceMessageFrameKeys.MESSAGE_TYPE put message.messageType
    correlationId?.wireValue?.let { DeviceMessageFrameKeys.CORRELATION_ID put it }
    verifiedIdentity?.takeIf { it.isNotBlank() }?.let { DeviceMessageFrameKeys.VERIFIED_IDENTITY put it }
    hlcTimestamp?.let { stamp ->
        DeviceMessageFrameKeys.HLC_PHYSICAL_MS put stamp.physicalMilliseconds
        DeviceMessageFrameKeys.HLC_LOGICAL put stamp.logicalCounter
        if (!stamp.nodeId.isUnspecified()) DeviceMessageFrameKeys.HLC_NODE put stamp.nodeId.value
    }
    set(DeviceMessageFrameKeys.ATTRIBUTES, attributes)
    Envelope.ENVELOPE_TYPE_KEY put DeviceMessageFrameKeys.ENVELOPE_TYPE
    Envelope.ENVELOPE_DATA_TYPE_KEY put DeviceMessageFrameKeys.JSON_DATA_TYPE
}

private fun Meta.toMessageContext(): MessageContext {
    val physical = get(DeviceMessageFrameKeys.HLC_PHYSICAL_MS)?.long
    val logical = get(DeviceMessageFrameKeys.HLC_LOGICAL)?.long
    val nodeId = get(DeviceMessageFrameKeys.HLC_NODE)?.string?.let(::HlcNodeId) ?: HlcNodeId.Unspecified
    return MessageContext(
        correlationId = CorrelationId.fromWire(get(DeviceMessageFrameKeys.CORRELATION_ID)?.string),
        hlcTimestamp = if (physical != null && logical != null) HlcTimestamp(physical, logical, nodeId) else null,
        verifiedIdentity = get(DeviceMessageFrameKeys.VERIFIED_IDENTITY)?.string,
        attributes = get(DeviceMessageFrameKeys.ATTRIBUTES) ?: Meta.EMPTY,
    )
}
