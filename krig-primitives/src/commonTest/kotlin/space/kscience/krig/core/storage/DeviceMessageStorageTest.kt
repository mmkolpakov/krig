package space.kscience.krig.core.storage

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import space.kscience.krig.api.messages.DeviceMessage
import space.kscience.krig.api.messages.DeviceMessageType
import space.kscience.krig.api.messages.PropertyChangedMessage
import space.kscience.krig.core.ExperimentalKrigApi
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.asName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock

@OptIn(ExperimentalKrigApi::class)
class DeviceMessageStorageTest {
    @Test
    fun typedReadUsesMessageTypeWithoutSerializerModule() = runTest {
        val storage = InMemoryDeviceMessageStorage()
        val message = PropertyChangedMessage(
            time = Clock.System.now(),
            property = "temperature".asName(),
            value = Meta { "value" put 42 },
            sourceDevice = "thermo".asName(),
        )

        storage.write(message)

        assertEquals(listOf(message), storage.read<PropertyChangedMessage>().toList())
        assertEquals(DeviceMessageType.PropertyChanged, messageTypeFor(PropertyChangedMessage::class))
        assertEquals(
            listOf(message),
            storage.readTyped<PropertyChangedMessage>(DeviceMessageType.PropertyChanged).toList(),
        )
        assertEquals(listOf(message), storage.read(DeviceMessageType.PropertyChanged).toList())
        assertEquals(listOf<DeviceMessage>(message), storage.read<DeviceMessage>().toList())
    }

    @Test
    fun hotTailPreservesAppendOrderForBatchWrites() = runTest {
        val storage = InMemoryDeviceMessageStorage(tailBufferCapacity = 16)
        val messages = (0 until 5).map { index ->
            PropertyChangedMessage(
                time = Clock.System.now(),
                property = "temperature".asName(),
                value = Meta { "value" put index },
                sourceDevice = "thermo".asName(),
            )
        }
        val observed = async(start = CoroutineStart.UNDISPATCHED) {
            storage.observe().take(messages.size).toList()
        }
        yield()

        storage.writeAll(messages)

        assertEquals(messages, observed.await())
    }
}
