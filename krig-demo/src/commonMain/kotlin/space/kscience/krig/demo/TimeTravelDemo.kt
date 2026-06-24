@file:OptIn(
    space.kscience.krig.core.ExperimentalKrigApi::class,
    ExperimentalTimeTravelApi::class,
)

package space.kscience.krig.demo

import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.PolymorphicSerializer
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.asValue
import space.kscience.dataforge.meta.int
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName
import space.kscience.krig.api.data.DeviceSnapshot
import space.kscience.krig.api.messages.DeviceMessage
import space.kscience.krig.api.messages.PropertyChangedMessage
import space.kscience.krig.api.messages.frame
import space.kscience.krig.api.messages.withHlcStamp
import space.kscience.krig.api.meta.serializableToMeta
import space.kscience.krig.api.serialization.krigStorageJson
import space.kscience.krig.api.data.HlcTimestamp
import space.kscience.krig.core.timetravel.CursorJournal
import space.kscience.krig.core.timetravel.ExperimentalTimeTravelApi
import space.kscience.krig.core.timetravel.InMemorySnapshotStore
import space.kscience.krig.core.timetravel.JournalEntry
import space.kscience.krig.core.timetravel.JournalMigration
import space.kscience.krig.core.timetravel.JournalRecord
import space.kscience.krig.core.timetravel.StorageSchema
import space.kscience.krig.core.timetravel.StorageSchemas
import space.kscience.krig.core.timetravel.JournalMigrations
import space.kscience.krig.core.timetravel.KotlinxJsonJournalPayloadCodec
import space.kscience.krig.core.timetravel.MessageJournalCodec
import space.kscience.krig.core.timetravel.Reconstructible
import space.kscience.krig.core.timetravel.asReplayLog
import space.kscience.krig.core.timetravel.counterfactual
import space.kscience.krig.core.timetravel.counterfactualScope
import space.kscience.krig.core.timetravel.recover
import space.kscience.krig.core.timetravel.save
import space.kscience.krig.core.timetravel.timeTravel
import space.kscience.krig.storage.journal.EventCursor
import space.kscience.krig.storage.journal.InMemoryEventJournal
import space.kscience.krig.storage.journal.SequenceCursor
import kotlin.time.Instant

/**
 * Time-travel walkthrough: replay, snapshots, branches and schema migration.
 *
 * Run: `./gradlew :krig-demo:jvmRun`
 */
suspend fun timeTravelDemo() {
    val source = "lab.counter".asName()

    println("=== 1. Event log ===")

    val log = InMemoryEventJournal()
    repeat(5) { i ->
        log.record(
            PropertyChangedMessage(
                time = Instant.fromEpochMilliseconds((i + 1) * 1000L),
                property = "value".asName(),
                value = Meta(i.asValue()),
                sourceDevice = source,
            ).frame(),
        )
    }
    println("  recorded ${log.size()} events: 0..4")

    println("\n=== 2. Time-travel ===")

    val replay = CounterReplay()
    replay.timeTravel(
        at = Instant.fromEpochMilliseconds(3000),
        log = log,
    )
    println("  state at t=3000ms: value = ${replay.value}")

    println("\n=== 3. Snapshot recovery ===")

    val snapshotStore = InMemorySnapshotStore()
    snapshotStore.save(source, replay.captureSnapshot(Instant.fromEpochMilliseconds(3000)))
    val recovered = CounterReplay()
    recovered.recover {
        subject = source
        at = Instant.fromEpochMilliseconds(5000)
        this.log = log
        snapshots = snapshotStore
    }
    println("  recovered from snapshot + delta: value = ${recovered.value}")

    println("\n=== 4. Counterfactual ===")

    val cf = CounterReplay()
    cf.counterfactual(log, at = Instant.fromEpochMilliseconds(5000)) { event ->
        if (event is PropertyChangedMessage && event.property == "value".asName() &&
            event.time == Instant.fromEpochMilliseconds(2000)
        ) {
            event.copy(value = Meta(42.asValue()))
        } else event
    }
    println("  what-if state: value = ${cf.value}")

    println("\n=== 5. Cursor counterfactual DSL ===")

    val cfd = CounterReplay()
    cfd.counterfactualScope(
        log = log,
        at = Instant.fromEpochMilliseconds(5000),
    ) {
        mutate(SequenceCursor(4), "value".asName()) {
            Meta(99.asValue())
        }
    }
    println("  cursor-targeted result: value = ${cfd.value}")

    println("\n=== 6. Journal migration ===")

    val migrated = CounterReplay()
    migrated.timeTravel(
        at = Instant.fromEpochMilliseconds(6000),
        log = oldCounterLog(source).asReplayLog(counterMigrationCodec()),
    )
    println("  old journal entry migrated: value = ${migrated.value}")

    println("\n=== 7. Causal ordering ===")

    val causalLog = InMemoryEventJournal()
    causalLog.record(
        counterMessage(source, second = 3, value = 300).frame().withHlcStamp(HlcTimestamp(100, 0)),
    )
    causalLog.record(
        counterMessage(source, second = 1, value = 100).frame().withHlcStamp(HlcTimestamp(300, 0)),
    )
    causalLog.record(
        counterMessage(source, second = 2, value = 200).frame().withHlcStamp(HlcTimestamp(200, 0)),
    )
    val causalOrder = causalLog
        .replay(from = Instant.fromEpochMilliseconds(0), until = Instant.fromEpochMilliseconds(10_000))
        .toList()
        .mapNotNull { (it.payload as? PropertyChangedMessage)?.value?.int }
    println("  HLC replay order: $causalOrder")

    println("\nDone - time-travel demo complete.")
}

private val oldCounterSchema = StorageSchema("demo.counter.scalar.v0")

private fun oldCounterLog(source: Name): CursorJournal {
    val entry = JournalEntry(
        subject = source,
        messageType = "demo.counter.scalar",
        schema = oldCounterSchema,
        time = Instant.fromEpochMilliseconds(6000),
        payload = Meta(77.asValue()),
    )
    val records = listOf(JournalRecord(SequenceCursor(0), entry))
    return object : CursorJournal {
        override fun replayEntries(after: EventCursor?) =
            records
                .filter { after == null || it.cursor > after }
                .asFlow()
    }
}

private fun counterMessage(source: Name, second: Int, value: Int): PropertyChangedMessage =
    PropertyChangedMessage(
        time = Instant.fromEpochMilliseconds(second * 1_000L),
        property = "value".asName(),
        value = Meta(value.asValue()),
        sourceDevice = source,
    )

private fun counterMigrationCodec(): MessageJournalCodec {
    val json = krigStorageJson()
    val serializer = PolymorphicSerializer(DeviceMessage::class)
    val migration = JournalMigration { entry ->
        if (entry.schema != oldCounterSchema) {
            entry
        } else {
            val value = entry.payload.int ?: 0
            val message = PropertyChangedMessage(
                time = entry.time,
                property = "value".asName(),
                value = Meta(value.asValue()),
                sourceDevice = entry.subject,
            )
            entry.copy(
                messageType = message.messageType,
                schema = StorageSchemas.deviceMessageV1,
                payload = serializableToMeta(serializer, message, json),
            )
        }
    }
    return MessageJournalCodec(
        payloadCodec = KotlinxJsonJournalPayloadCodec(json),
        migrations = JournalMigrations(migration),
    )
}

private class CounterReplay : Reconstructible {
    var value: Int = 0
        private set

    override suspend fun applyEvent(event: DeviceMessage) {
        val m = event as? PropertyChangedMessage ?: return
        if (m.property == "value".asName()) value = m.value.int ?: value
    }

    override suspend fun captureSnapshot(at: Instant): DeviceSnapshot =
        DeviceSnapshot(at = at, state = Meta(value.asValue()))

    override suspend fun restoreSnapshot(snapshot: DeviceSnapshot) {
        value = snapshot.state.int ?: error("snapshot corrupt")
    }
}
