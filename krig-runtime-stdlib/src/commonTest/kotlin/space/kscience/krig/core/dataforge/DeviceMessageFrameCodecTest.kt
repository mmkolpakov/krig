package space.kscience.krig.core.dataforge

import space.kscience.dataforge.meta.get
import space.kscience.dataforge.meta.string
import space.kscience.dataforge.names.asName
import space.kscience.krig.api.identifiers.CorrelationId
import space.kscience.krig.api.messages.DeviceMessageType
import space.kscience.krig.api.messages.MessageContext
import space.kscience.krig.api.messages.PropertyChangedMessage
import space.kscience.krig.api.messages.frame
import space.kscience.krig.core.operations.HlcTimestamp
import space.kscience.dataforge.meta.MetaConverter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class DeviceMessageFrameCodecTest {

    private val codec = KotlinxJsonDeviceMessageFrameCodec()

    @Test
    fun loweringToDataForgeEnvelopeRoundTrips() {
        val message = PropertyChangedMessage(
            time = Instant.fromEpochMilliseconds(7),
            property = "rpm".asName(),
            value = MetaConverter.double.convert(1_500.0),
            sourceDevice = "pump".asName(),
        )
        val frame = message.frame(
            MessageContext(
                correlationId = CorrelationId("trace-7"),
                hlcTimestamp = HlcTimestamp(physicalMilliseconds = 42, logicalCounter = 3),
            ),
        )

        val envelope = codec.encode(frame)

        // Schemaless Meta header carries the canonical wire keys.
        assertEquals(
            DeviceMessageType.PropertyChanged,
            envelope.meta[DeviceMessageFrameKeys.MESSAGE_TYPE]?.string,
        )

        val decoded = codec.decode(envelope)
        assertEquals(frame.payload, decoded.payload)
        assertEquals(CorrelationId("trace-7"), decoded.context.correlationId)
        assertEquals(HlcTimestamp(42, 3), decoded.context.hlcTimestamp)
    }
}
