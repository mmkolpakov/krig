package space.kscience.krig.storage.journal

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import space.kscience.krig.api.messages.DeviceMessage
import space.kscience.krig.api.messages.DeviceMessageType
import space.kscience.krig.api.messages.PropertyChangedMessage
import space.kscience.krig.api.messages.frame
import space.kscience.krig.api.serialization.krigStorageJson
import space.kscience.krig.core.ExperimentalKrigApi
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.asName
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock

@OptIn(ExperimentalKrigApi::class)
class EventJournalTest {
    @Test
    fun typedReadUsesMessageTypeWithoutSerializerModule() = runTest {
        val storage = InMemoryEventJournal()
        val message = PropertyChangedMessage(
            time = Clock.System.now(),
            property = "temperature".asName(),
            value = Meta { "value" put 42 },
            sourceDevice = "thermo".asName(),
        )

        storage.write(message)

        assertEquals(listOf(message), storage.read<PropertyChangedMessage>().toList())
        assertEquals(
            listOf(message),
            storage.readTyped<PropertyChangedMessage>(DeviceMessageType.PropertyChanged).toList(),
        )
        assertEquals(
            emptyList(),
            storage.readTyped<PropertyChangedMessage>("custom.property-changed").toList(),
        )
        assertEquals(listOf(message.frame()), storage.read(DeviceMessageType.PropertyChanged).toList())
        assertEquals(listOf<DeviceMessage>(message), storage.read<DeviceMessage>().toList())
    }

    @Test
    fun truncateBeforeDropsRecordsUpToCursor() = runTest {
        val storage = InMemoryEventJournal()
        val cursors = (0 until 5).map { index ->
            storage.write(
                PropertyChangedMessage(
                    time = Clock.System.now(),
                    property = "temperature".asName(),
                    value = Meta { "value" put index },
                    sourceDevice = "thermo".asName(),
                ),
            )
        }

        storage.truncateBefore(cursors[2])

        val remaining = storage.readAll().toList().map { (it.payload as PropertyChangedMessage).value }
        assertEquals(listOf(Meta { "value" put 3 }, Meta { "value" put 4 }), remaining)
        assertEquals(2, storage.size())
    }

    @Test
    fun checkpointAnchorSerializesSequenceCursor() {
        val json = krigStorageJson()
        val anchor = CheckpointAnchor(coveredCursor = SequenceCursor(42))

        val encoded = json.encodeToString(anchor)
        val decoded = json.decodeFromString<CheckpointAnchor>(encoded)

        assertEquals(anchor, decoded)
    }

    @Test
    fun hotTailPreservesAppendOrderForBatchWrites() = runTest {
        val storage = InMemoryEventJournal(tailBufferCapacity = 16)
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

        storage.writePayloads(messages)

        assertEquals(messages, observed.await().map { it.payload })
    }
}
