package space.kscience.krig.demo

import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.parseAsName
import space.kscience.krig.api.messages.DeviceMessage
import space.kscience.krig.api.messages.DeviceMessageFrame
import space.kscience.krig.api.messages.KrigWireFormats
import space.kscience.krig.api.serialization.krigJson
import space.kscience.magix.api.MagixMessage
import space.kscience.magix.api.decodeEnvelopeOrNull
import space.kscience.magix.api.encodeEnvelope

internal data class MagixFrameRoundTrip<T : DeviceMessage>(
    val message: MagixMessage,
    val innerFormat: String?,
    val topic: Name?,
    val headers: JsonObject,
    val payload: T,
)

internal inline fun <reified T : DeviceMessage> roundTripKrigFrameThroughMagix(
    frame: DeviceMessageFrame<DeviceMessage>,
    topic: Name,
    headers: JsonObject = buildJsonObject { },
    json: Json = krigJson(),
    sourceEndpoint: Name = "krig.edge".parseAsName(),
    targetEndpoint: Name = "krig.analytics".parseAsName(),
): MagixFrameRoundTrip<T> {
    val frameSerializer = DeviceMessageFrame.serializer(PolymorphicSerializer(DeviceMessage::class))
    val wireFrame = DeviceMessageFrame<DeviceMessage>(frame.payload, frame.context)
    val payload = json.encodeToJsonElement(frameSerializer, wireFrame)
    val envelopePayload = JsonElement.encodeEnvelope(
        json = json,
        serializer = JsonElement.serializer(),
        payload = payload,
        topic = topic,
        format = KrigWireFormats.DeviceMessageFrame,
        headers = headers,
    )
    val message = MagixMessage(
        format = KrigWireFormats.MagixEnvelope,
        payload = envelopePayload,
        sourceEndpoint = sourceEndpoint,
        targetEndpoint = targetEndpoint,
    )
    val envelope = message.payload.decodeEnvelopeOrNull(json, JsonElement.serializer())
        ?: error("Expected KRig Magix envelope")
    val decodedFrame = json.decodeFromJsonElement(frameSerializer, envelope.data)
    val decodedPayload = decodedFrame.payload as? T
        ?: error("Unexpected decoded payload type: ${decodedFrame.payload.messageType}")
    return MagixFrameRoundTrip(
        message = message,
        innerFormat = envelope.format,
        topic = envelope.topic,
        headers = envelope.headers,
        payload = decodedPayload,
    )
}
