package space.kscience.krig.demo

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName
import space.kscience.dataforge.names.parseAsName
import space.kscience.krig.api.data.QualitySeverity
import space.kscience.krig.api.messages.KrigWireHeaders
import space.kscience.krig.api.messages.KrigWireTopics
import space.kscience.krig.api.messages.frame
import space.kscience.krig.api.serialization.krigJson
import space.kscience.krig.flow.DistributedFlowTransfer
import space.kscience.krig.flow.FlowMessageType
import space.kscience.krig.flow.FlowRate
import space.kscience.krig.flow.FlowTransferMessage
import space.kscience.krig.flow.FlowUnits
import space.kscience.krig.flow.flowGraph
import space.kscience.krig.flow.krigFlowSerializationContributor
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

internal data class DistributedFlowTransferSnapshot(
    val outerFormat: String,
    val innerFormat: String?,
    val topic: Name?,
    val messageType: String,
    val amount: Double,
    val sequence: Long?,
    val delayedQualitySeverity: Int,
    val delayedQualityCode: String?,
)

/** Flow transfer crossing a broker boundary as an explicit message frame. */
suspend fun distributedFlowTransferDemo() {
    val snapshot = distributedFlowTransferSnapshot()

    println("=== Distributed flow transfer ===")
    println("  outer format: ${snapshot.outerFormat}")
    println("  inner format: ${snapshot.innerFormat}")
    println("  topic: ${snapshot.topic}")
    println("  message type: ${snapshot.messageType}")
    println("  amount: ${snapshot.amount}")
    println("  delayed quality: ${snapshot.delayedQualityCode}")
    println("\nDone - distributed flow transfer demo complete.")
}

internal fun distributedFlowTransferSnapshot(): DistributedFlowTransferSnapshot {
    val unit = FlowUnits.Kilogram
    val graph = flowGraph {
        producer("edgeSource", unit, productionRate = FlowRate(2.0))
        delayed("transportDelay", unit, delaySteps = 2)
        consumer("analyticsSink", unit, capacity = FlowRate(2.0))
        connect("edgeSource", "transportDelay")
        connect("transportDelay", "analyticsSink")
    }
    val report = graph.step(1.seconds)
    val transfer = report.transfers.first { it.amount.value > 0.0 }
    val delayedQuality = report.snapshot.blocks.getValue("transportDelay".asName()).quality
    val time = Instant.fromEpochMilliseconds(1)
    val frame = FlowTransferMessage(
        time = time,
        sourceDevice = "edge.lineA.flow".parseAsName(),
        targetDevice = "analytics.lineA.flow".parseAsName(),
        transfer = DistributedFlowTransfer(
            connection = transfer.connection,
            amount = transfer.amount,
            unit = unit,
            sequence = 1,
            effectiveAt = time + 2.seconds,
            quality = delayedQuality,
        ),
    ).frame()

    val json = krigJson(krigFlowSerializationContributor)
    val topic = KrigWireTopics.deviceMessages("edge.lineA.flow".parseAsName())
    val roundTrip = roundTripKrigFrameThroughMagix<FlowTransferMessage>(
        frame = frame,
        topic = topic,
        headers = buildJsonObject {
            put(KrigWireHeaders.MessageType, JsonPrimitive(FlowMessageType.Transfer))
        },
        json = json,
    )

    return DistributedFlowTransferSnapshot(
        outerFormat = roundTrip.message.format,
        innerFormat = roundTrip.innerFormat,
        topic = roundTrip.topic,
        messageType = roundTrip.payload.messageType,
        amount = roundTrip.payload.transfer.amount.value,
        sequence = roundTrip.payload.transfer.sequence,
        delayedQualitySeverity = roundTrip.payload.transfer.quality.severity.rank,
        delayedQualityCode = roundTrip.payload.transfer.quality.code?.id,
    ).also {
        check(it.delayedQualitySeverity == QualitySeverity.UNCERTAIN.rank)
    }
}
