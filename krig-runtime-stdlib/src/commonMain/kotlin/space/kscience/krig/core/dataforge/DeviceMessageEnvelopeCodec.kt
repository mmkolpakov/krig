package space.kscience.krig.core.dataforge

import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.json.Json
import space.kscience.dataforge.io.Binary
import space.kscience.dataforge.io.Envelope
import space.kscience.dataforge.io.asBinary
import space.kscience.dataforge.io.toByteArray
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.int
import space.kscience.dataforge.meta.long
import space.kscience.dataforge.meta.string
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.parseAsName
import space.kscience.krig.api.messages.DeviceMessage
import space.kscience.krig.api.messages.DeviceMessageEnvelope
import space.kscience.krig.api.messages.MessageContext
import space.kscience.krig.api.serialization.krigStorageJson
import space.kscience.krig.core.operations.HlcTimestamp

public object DeviceMessageEnvelopeSchema {
    public val MESSAGE_TYPE: Name = "krig.message.type".parseAsName()
    public val REQUEST_ID: Name = "krig.message.requestId".parseAsName()
    public val CORRELATION_ID: Name = "krig.message.correlationId".parseAsName()
    public val HLC_PHYSICAL_MS: Name = "krig.message.hlc.physicalMs".parseAsName()
    public val HLC_LOGICAL: Name = "krig.message.hlc.logical".parseAsName()
    public val ATTRIBUTES: Name = "krig.message.attributes".parseAsName()
    public const val ENVELOPE_TYPE: String = "krig.device-message"
    public const val JSON_DATA_TYPE: String = "application/vnd.krig.device-message+json"
}

public interface DeviceMessageEnvelopeCodec {
    public fun encode(envelope: DeviceMessageEnvelope<DeviceMessage>): Envelope
    public fun decode(envelope: Envelope): DeviceMessageEnvelope<DeviceMessage>
}

public class KotlinxJsonDeviceMessageEnvelopeCodec(
    private val json: Json = krigStorageJson(),
) : DeviceMessageEnvelopeCodec {
    private val serializer = PolymorphicSerializer(DeviceMessage::class)

    override fun encode(envelope: DeviceMessageEnvelope<DeviceMessage>): Envelope {
        val payload = json.encodeToString(serializer, envelope.payload).encodeToByteArray().asBinary()
        return Envelope(envelope.context.toEnvelopeMeta(envelope.payload), payload)
    }

    override fun decode(envelope: Envelope): DeviceMessageEnvelope<DeviceMessage> {
        val payloadBinary = envelope.data ?: Binary.EMPTY
        val payloadText = payloadBinary.toByteArray().decodeToString()
        return DeviceMessageEnvelope(
            payload = json.decodeFromString(serializer, payloadText),
            context = envelope.meta.toMessageContext(),
        )
    }
}

private fun MessageContext.toEnvelopeMeta(message: DeviceMessage): Meta = Meta {
    DeviceMessageEnvelopeSchema.MESSAGE_TYPE put message.messageType
    requestId?.let { DeviceMessageEnvelopeSchema.REQUEST_ID put it }
    correlationId?.let { DeviceMessageEnvelopeSchema.CORRELATION_ID put it }
    hlcTimestamp?.let { stamp ->
        DeviceMessageEnvelopeSchema.HLC_PHYSICAL_MS put stamp.physicalMilliseconds
        DeviceMessageEnvelopeSchema.HLC_LOGICAL put stamp.logicalCounter
    }
    set(DeviceMessageEnvelopeSchema.ATTRIBUTES, attributes)
    Envelope.ENVELOPE_TYPE_KEY put DeviceMessageEnvelopeSchema.ENVELOPE_TYPE
    Envelope.ENVELOPE_DATA_TYPE_KEY put DeviceMessageEnvelopeSchema.JSON_DATA_TYPE
}

private fun Meta.toMessageContext(): MessageContext {
    val physical = get(DeviceMessageEnvelopeSchema.HLC_PHYSICAL_MS)?.long
    val logical = get(DeviceMessageEnvelopeSchema.HLC_LOGICAL)?.int
    return MessageContext(
        requestId = get(DeviceMessageEnvelopeSchema.REQUEST_ID)?.string,
        correlationId = get(DeviceMessageEnvelopeSchema.CORRELATION_ID)?.string,
        hlcTimestamp = if (physical != null && logical != null) HlcTimestamp(physical, logical) else null,
        attributes = get(DeviceMessageEnvelopeSchema.ATTRIBUTES) ?: Meta.EMPTY,
    )
}
