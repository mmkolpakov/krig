package space.kscience.krig.api.messages

import kotlinx.serialization.PolymorphicSerializer
import space.kscience.dataforge.meta.MetaConverter
import space.kscience.dataforge.names.asName
import space.kscience.krig.api.serialization.krigStorageJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class DeviceMessageEnvelopeTest {

    private val json = krigStorageJson()
    private val messageSerializer = PolymorphicSerializer(DeviceMessage::class)
    private val envelopeSerializer = DeviceMessageEnvelope.serializer(messageSerializer)

    @Test
    fun storageJsonOmitsDefaultEnvelopeContext() {
        val message = PropertyChangedMessage(
            time = Instant.fromEpochMilliseconds(1),
            property = "rpm".asName(),
            value = MetaConverter.double.convert(1_200.0),
            sourceDevice = "pump".asName(),
        )

        val encoded = json.encodeToString(envelopeSerializer, message.envelope())

        assertTrue("\"payload\"" in encoded)
        assertFalse("\"context\"" in encoded)
        assertFalse("\"requestId\"" in encoded)
        assertFalse("\"correlationId\"" in encoded)
    }

    @Test
    fun envelopeCarriesCorrelationOutsidePayload() {
        val message = DeviceOnlineMessage(
            time = Instant.fromEpochMilliseconds(1),
            manifestId = "demo.pump".asName(),
            sourceDevice = "pump".asName(),
        )
        val envelope = message.envelope(
            MessageContext(correlationId = "trace-1"),
        )

        assertEquals("trace-1", envelope.context.correlationId)
    }
}
