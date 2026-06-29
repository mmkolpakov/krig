package space.kscience.krig.demo

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.parseAsName
import space.kscience.krig.api.messages.KrigWireHeaders
import space.kscience.krig.api.messages.KrigWireTopics
import space.kscience.krig.api.messages.PropertyChangedMessage
import space.kscience.krig.api.messages.frame
import space.kscience.krig.core.contracts.metaOf
import space.kscience.krig.core.contracts.schemaHash
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
    val topic = KrigWireTopics.deviceMessages("edge.lineA.pump".parseAsName())
    val roundTrip = roundTripKrigFrameThroughMagix<PropertyChangedMessage>(
        frame = PropertyChangedMessage(
            time = Instant.fromEpochMilliseconds(1),
            property = PumpSpec.rpm.name,
            value = metaOf(1_200.0),
            sourceDevice = "edge.lineA.pump".parseAsName(),
        ).frame(),
        topic = topic,
        headers = buildJsonObject {
            put(KrigWireHeaders.SchemaHash, JsonPrimitive(PumpManifest.schemaHash()))
        },
    )
    return MagixEnvelopeSnapshot(
        outerFormat = roundTrip.message.format,
        innerFormat = roundTrip.innerFormat,
        topic = roundTrip.topic,
        messageType = roundTrip.payload.messageType,
        schemaHeader = roundTrip.headers[KrigWireHeaders.SchemaHash]?.jsonPrimitive?.content,
    )
}
