package space.kscience.krig.flow

import kotlinx.serialization.PolymorphicSerializer
import space.kscience.dataforge.names.asName
import space.kscience.krig.api.messages.DeviceMessage
import space.kscience.krig.api.serialization.krigJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.time.Instant

class DistributedFlowTransferTest {

    @Test
    fun flowTransferMessageRoundTripsThroughContributor() {
        val json = krigJson(krigFlowSerializationContributor)
        val message = FlowTransferMessage(
            time = Instant.fromEpochMilliseconds(1),
            sourceDevice = "edge.flow".asName(),
            targetDevice = "analytics.flow".asName(),
            transfer = DistributedFlowTransfer(
                connection = FlowConnection(
                    source = FlowEndpoint("producer".asName(), "out".asName()),
                    target = FlowEndpoint("delay".asName(), "in".asName()),
                ),
                amount = FlowAmount(2.0),
                unit = FlowUnits.Kilogram,
                sequence = 7,
            ),
        )

        val encoded = json.encodeToString(PolymorphicSerializer(DeviceMessage::class), message)
        val decoded = json.decodeFromString(PolymorphicSerializer(DeviceMessage::class), encoded)

        val transfer = assertIs<FlowTransferMessage>(decoded).transfer
        assertEquals(FlowMessageType.Transfer, decoded.messageType)
        assertEquals(2.0, transfer.amount.value)
        assertEquals(7, transfer.sequence)
    }

    @Test
    fun distributedTransferRejectsNegativeSequence() {
        assertFailsWith<IllegalArgumentException> {
            DistributedFlowTransfer(
                connection = FlowConnection(
                    source = FlowEndpoint("producer".asName(), "out".asName()),
                    target = FlowEndpoint("delay".asName(), "in".asName()),
                ),
                amount = FlowAmount(1.0),
                unit = FlowUnits.Kilogram,
                sequence = -1,
            )
        }
    }
}
