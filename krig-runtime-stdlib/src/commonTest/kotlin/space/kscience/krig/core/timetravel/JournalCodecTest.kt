package space.kscience.krig.core.timetravel

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.PolymorphicSerializer
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.asValue
import space.kscience.dataforge.meta.int
import space.kscience.dataforge.names.asName
import space.kscience.krig.api.messages.DeviceMessage
import space.kscience.krig.api.messages.PropertyChangedMessage
import space.kscience.krig.api.meta.serializableToMeta
import space.kscience.krig.api.serialization.krigStorageJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class JournalCodecTest {
    private val source = "lab.counter".asName()

    @Test
    fun codecRoundTripsDeviceMessage() {
        val codec = MessageJournalCodec()
        val message = event(100, 3)

        val decoded = codec.decode(codec.encode(message)).single()

        assertEquals(message, decoded)
    }

    @Test
    fun migrationCanUpcastOldEntryBeforeReplay() = runTest {
        val oldSchema = JournalSchema("demo.counter.v0")
        val json = krigStorageJson()
        val serializer = PolymorphicSerializer(DeviceMessage::class)
        val migration = JournalMigration { entry ->
            if (entry.schema != oldSchema) {
                sequenceOf(entry)
            } else {
                val value = entry.payload.int ?: 0
                val message = event(entry.time.toEpochMilliseconds(), value)
                sequenceOf(
                    entry.copy(
                        messageType = message.messageType,
                        schema = JournalSchemas.deviceMessageV1,
                        payload = serializableToMeta(serializer, message, json),
                    ),
                )
            }
        }
        val codec = MessageJournalCodec(
            payloadCodec = KotlinxJsonJournalPayloadCodec(json),
            migrations = JournalMigrations(migration),
        )
        val raw = JournalEntry(
            subject = source,
            messageType = "demo.counter",
            schema = oldSchema,
            time = Instant.fromEpochMilliseconds(100),
            payload = Meta(9.asValue()),
        )
        val journal = object : CursorJournal {
            override fun replayEntries(after: EventCursor?) =
                flowOf(JournalRecord(SequenceCursor(0), raw))
        }

        val replay = journal.asReplayLog(codec).replayFrom().toList()

        assertEquals(1, replay.size)
        assertEquals(9, (replay.single().message as PropertyChangedMessage).value.int)
    }

    private fun event(t: Long, v: Int): PropertyChangedMessage =
        PropertyChangedMessage(
            time = Instant.fromEpochMilliseconds(t),
            property = "value".asName(),
            value = Meta(v.asValue()),
            sourceDevice = source,
        )
}
