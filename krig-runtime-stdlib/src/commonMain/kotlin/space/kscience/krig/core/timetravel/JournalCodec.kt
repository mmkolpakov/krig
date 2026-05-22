package space.kscience.krig.core.timetravel

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.takeWhile
import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.json.Json
import space.kscience.dataforge.names.Name
import space.kscience.krig.api.messages.DeviceMessage
import space.kscience.krig.api.messages.MessageContext
import space.kscience.krig.api.meta.serializableMetaConverter
import space.kscience.krig.api.meta.serializableToMeta
import space.kscience.krig.api.serialization.krigStorageJson
import kotlin.time.Instant

/** Transforms stored journal entries before decoding them into current DTOs. */
public fun interface JournalMigration {
    public fun migrate(entry: JournalEntry): Sequence<JournalEntry>
}

/** Ordered journal migration chain. A migration may drop, keep, replace, or split entries. */
public class JournalMigrations(
    private val migrations: List<JournalMigration> = emptyList(),
) {
    public constructor(vararg migrations: JournalMigration) : this(migrations.toList())

    public fun migrate(entry: JournalEntry): Sequence<JournalEntry> =
        migrations.fold(sequenceOf(entry)) { entries, migration ->
            entries.flatMap(migration::migrate)
        }

    public companion object {
        public val empty: JournalMigrations = JournalMigrations()
    }
}

/**
 * Encodes current [DeviceMessage]s and decodes stored [JournalEntry]s after migrations.
 */
public class MessageJournalCodec(
    private val json: Json = krigStorageJson(),
    private val migrations: JournalMigrations = JournalMigrations.empty,
    private val schema: JournalSchema = JournalSchemas.deviceMessageV1,
) {
    private val serializer = PolymorphicSerializer(DeviceMessage::class)

    public fun encode(
        message: DeviceMessage,
        context: MessageContext = MessageContext.Empty,
        subject: Name = message.sourceDevice ?: Name.EMPTY,
    ): JournalEntry = JournalEntry(
        subject = subject,
        messageType = message.messageType,
        schema = schema,
        time = message.time,
        payload = serializableToMeta(serializer, message, json),
        context = context,
    )

    public fun decode(entry: JournalEntry): Sequence<DeviceMessage> =
        migrations.migrate(entry).map { migrated ->
            serializableMetaConverter(serializer, json).read(migrated.payload)
        }
}

/** Raw cursor journal. Persistent integrations implement this and adapt through [asReplayLog]. */
public interface CursorJournal {
    public fun replayEntries(after: EventCursor? = null): Flow<JournalRecord>
}

public fun CursorJournal.asReplayLog(codec: MessageJournalCodec = MessageJournalCodec()): CursorReplayLog =
    object : CursorReplayLog {
        override fun replayFrom(after: EventCursor?): Flow<ReplayRecord> =
            flow {
                replayEntries(after).collect { record ->
                    for (message in codec.decode(record.entry)) {
                        emit(ReplayRecord(record.cursor, message))
                    }
                }
            }

        override fun replay(from: Instant, until: Instant): Flow<DeviceMessage> =
            replayFrom(null)
                .map { it.message }
                .dropWhile { it.time < from }
                .takeWhile { it.time <= until }
    }
