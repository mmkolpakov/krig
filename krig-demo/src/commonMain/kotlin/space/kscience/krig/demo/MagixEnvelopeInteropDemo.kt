package space.kscience.krig.demo

import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.parseAsName
import space.kscience.krig.api.messages.DeviceMessage
import space.kscience.krig.api.messages.DeviceMessageFrame
import space.kscience.krig.api.messages.KrigWireFormats
import space.kscience.krig.api.messages.KrigWireHeaders
import space.kscience.krig.api.messages.KrigWireTopics
import space.kscience.krig.api.messages.PropertyChangedMessage
import space.kscience.krig.api.messages.frame
import space.kscience.krig.api.serialization.krigApiSerializersModule
import space.kscience.krig.core.contracts.metaOf
import space.kscience.krig.core.contracts.schemaHash
import space.kscience.magix.api.MagixMessage
import space.kscience.magix.api.decodeEnvelopeOrNull
import space.kscience.magix.api.encodeEnvelope
import kotlin.time.Instant

internal data class MagixEnvelopeSnapshot(
    val outerFormat: String,
    val innerFormat: String?,
    val topic: Name?,
    val messageType: String,
    val schemaHeader: String?,
)

/** KRig message frame wrapped into a Magix envelope for strict relay hops. */
suspend fun magixEnvelopeInteropDemo() {
    val snapshot = magixEnvelopeSnapshot()

    println("=== Magix envelope interop ===")
    println("  outer format: ${snapshot.outerFormat}")
    println("  inner format: ${snapshot.innerFormat}")
    println("  topic: ${snapshot.topic}")
    println("  message type: ${snapshot.messageType}")
    println("\nDone - Magix envelope interop demo complete.")
}

internal fun magixEnvelopeSnapshot(): MagixEnvelopeSnapshot {
    val json = Json {
        serializersModule = krigApiSerializersModule
        encodeDefaults = false
        explicitNulls = false
    }
    val frameSerializer = DeviceMessageFrame.serializer(PolymorphicSerializer(DeviceMessage::class))
    val frame = PropertyChangedMessage(
        time = Instant.fromEpochMilliseconds(1),
        property = PumpSpec.rpm.name,
        value = metaOf(1_200.0),
        sourceDevice = "edge.lineA.pump".parseAsName(),
    ).frame()
    val payload = json.encodeToJsonElement(frameSerializer, frame)
    val topic = KrigWireTopics.deviceMessages("edge.lineA.pump".parseAsName())
    val envelopePayload = JsonElement.encodeEnvelope(
        json = json,
        serializer = JsonElement.serializer(),
        payload = payload,
        topic = topic,
        format = KrigWireFormats.DeviceMessageFrame,
        headers = buildJsonObject {
            put(KrigWireHeaders.SchemaHash, JsonPrimitive(PumpManifest.schemaHash()))
        },
    )
    val message = MagixMessage(
        format = KrigWireFormats.MagixEnvelope,
        payload = envelopePayload,
        sourceEndpoint = "krig.edge".parseAsName(),
        targetEndpoint = "krig.analytics".parseAsName(),
    )
    val envelope = message.payload.decodeEnvelopeOrNull(json, JsonElement.serializer())
        ?: error("Expected KRig Magix envelope")
    val decodedFrame = json.decodeFromJsonElement(frameSerializer, envelope.data)
    return MagixEnvelopeSnapshot(
        outerFormat = message.format,
        innerFormat = envelope.format,
        topic = envelope.topic,
        messageType = decodedFrame.payload.messageType,
        schemaHeader = envelope.headers[KrigWireHeaders.SchemaHash]?.jsonPrimitive?.content,
    )
}
