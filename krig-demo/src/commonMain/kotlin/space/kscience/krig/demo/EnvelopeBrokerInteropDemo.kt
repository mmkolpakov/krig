package space.kscience.krig.demo

import space.kscience.dataforge.io.Envelope
import space.kscience.dataforge.meta.string
import space.kscience.dataforge.names.asName
import space.kscience.krig.api.identifiers.CorrelationId
import space.kscience.krig.api.messages.KrigWireHeaders
import space.kscience.krig.api.messages.MessageContext
import space.kscience.krig.api.messages.PropertyChangedMessage
import space.kscience.krig.api.messages.frame
import space.kscience.krig.core.contracts.metaOf
import space.kscience.krig.core.dataforge.KotlinxJsonDeviceMessageFrameCodec
import space.kscience.krig.core.dataforge.toKrigWireHeaders
import kotlin.time.Instant

internal data class EnvelopeBrokerInteropSnapshot(
    val envelopeType: String?,
    val dataType: String?,
    val messageTypeHeader: String?,
    val correlationHeader: String?,
    val decodedCorrelationId: CorrelationId?,
)

/** DataForge Envelope lowered to broker headers and decoded back to a typed KRig frame. */
fun envelopeBrokerInteropDemo() {
    val snapshot = envelopeBrokerInteropSnapshot()

    println("=== Envelope broker interop ===")
    println("  envelope type: ${snapshot.envelopeType}")
    println("  data type: ${snapshot.dataType}")
    println("  message type header: ${snapshot.messageTypeHeader}")
    println("  correlation header: ${snapshot.correlationHeader}")
    println("\nDone - envelope broker interop demo complete.")
}

internal fun envelopeBrokerInteropSnapshot(): EnvelopeBrokerInteropSnapshot {
    val codec = KotlinxJsonDeviceMessageFrameCodec()
    val frame = PropertyChangedMessage(
        time = Instant.fromEpochMilliseconds(1),
        property = PumpSpec.rpm.name,
        value = metaOf(1_200.0),
        sourceDevice = "edge.lineA.pump".asName(),
    ).frame(MessageContext(correlationId = CorrelationId("trace-42")))
    val envelope = codec.encode(frame)
    val headers = envelope.toKrigWireHeaders()
    val decoded = codec.decode(envelope)

    return EnvelopeBrokerInteropSnapshot(
        envelopeType = envelope.meta[Envelope.ENVELOPE_TYPE_KEY]?.string,
        dataType = envelope.meta[Envelope.ENVELOPE_DATA_TYPE_KEY]?.string,
        messageTypeHeader = headers[KrigWireHeaders.MessageType],
        correlationHeader = headers[KrigWireHeaders.CorrelationId],
        decodedCorrelationId = decoded.context.correlationId,
    )
}
