package space.kscience.krig.core.timetravel

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.takeWhile
import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.json.Json
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.Name
import space.kscience.krig.api.messages.DeviceMessage
import space.kscience.krig.api.messages.DeviceMessageFrame
import space.kscience.krig.api.messages.MessageContext
import space.kscience.krig.api.meta.serializableMetaConverter
import space.kscience.krig.api.meta.serializableToMeta
import space.kscience.krig.api.serialization.krigStorageJson
import space.kscience.krig.storage.journal.CursorReplayLog
import space.kscience.krig.storage.journal.EventCursor
import space.kscience.krig.storage.journal.ReplayRecord
import kotlin.time.Instant

/**
 * Transforms one stored journal entry before decoding it into a current DTO. A migration may keep,
 * replace, or drop ([migrate] returns `null`) an entry, but **cannot split** it into several: a
 * stored entry is one physical fact bound to one storage cursor, so a one-to-many rewrite would make
 * a branch on a sub-entry ambiguous. Schema drift changes the payload, not the count of facts.
 */
public fun interface JournalMigration {
    public fun migrate(entry: JournalEntry): JournalEntry?
}

/** Ordered journal migration chain. Each migration may drop, keep, or replace an entry (never split). */
public class JournalMigrations(
    private val migrations: List<JournalMigration> = emptyList(),
) {
    public constructor(vararg migrations: JournalMigration) : this(migrations.toList())

    public fun migrate(entry: JournalEntry): JournalEntry? =
        migrations.fold<JournalMigration, JournalEntry?>(entry) { current, migration ->
            current?.let(migration::migrate)
        }

    public companion object {
        public val empty: JournalMigrations = JournalMigrations()
    }
}

/**
 * Encodes and decodes journal payloads.
 */
public interface JournalPayloadCodec {
    public fun encode(message: DeviceMessage): Meta

    public fun decode(payload: Meta): DeviceMessage
}

/** JSON-backed journal payload codec. */
public class KotlinxJsonJournalPayloadCodec(
    private val json: Json = krigStorageJson(),
) : JournalPayloadCodec {
    private val serializer = PolymorphicSerializer(DeviceMessage::class)

    override fun encode(message: DeviceMessage): Meta =
        serializableToMeta(serializer, message, json)

    override fun decode(payload: Meta): DeviceMessage =
        serializableMetaConverter(serializer, json).read(payload)
}

/**
 * Encodes current [DeviceMessage]s and decodes stored [JournalEntry]s after migrations.
 */
public class MessageJournalCodec(
    private val payloadCodec: JournalPayloadCodec = KotlinxJsonJournalPayloadCodec(),
    private val migrations: JournalMigrations = JournalMigrations.empty,
    private val schema: StorageSchema = StorageSchemas.deviceMessageV1,
) {
    public fun encode(
        message: DeviceMessage,
        context: MessageContext = MessageContext.Empty,
        subject: Name = message.sourceDevice ?: Name.EMPTY,
    ): JournalEntry = JournalEntry(
        subject = subject,
        messageType = message.messageType,
        schema = schema,
        time = message.time,
        payload = payloadCodec.encode(message),
        context = context,
    )

    public fun encode(
        envelope: DeviceMessageFrame<DeviceMessage>,
        subject: Name = envelope.payload.sourceDevice ?: Name.EMPTY,
    ): JournalEntry = encode(envelope.payload, envelope.context, subject)

    /** Decodes the (possibly migrated) message, or `null` when a migration dropped the entry. */
    public fun decode(entry: JournalEntry): DeviceMessage? =
        decodeEnvelope(entry)?.payload

    /** Decodes the (possibly migrated) frame, or `null` when a migration dropped the entry. */
    public fun decodeEnvelope(entry: JournalEntry): DeviceMessageFrame<DeviceMessage>? =
        migrations.migrate(entry)?.let { migrated ->
            DeviceMessageFrame(
                payload = payloadCodec.decode(migrated.payload),
                context = migrated.context,
            )
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
                    codec.decodeEnvelope(record.entry)?.let { envelope ->
                        emit(ReplayRecord(record.cursor, envelope))
                    }
                }
            }

        override fun replay(from: Instant, until: Instant): Flow<DeviceMessageFrame<DeviceMessage>> =
            replayFrom(null)
                .map { it.envelope }
                .dropWhile { it.payload.time < from }
                .takeWhile { it.payload.time <= until }
    }
