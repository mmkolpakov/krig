@file:OptIn(space.kscience.krig.core.ExperimentalKrigApi::class)

package space.kscience.krig.core.timetravel

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import space.kscience.krig.api.messages.DeviceAttachedMessage
import space.kscience.krig.api.messages.DeviceMessage
import space.kscience.krig.api.messages.withHlcStamp
import space.kscience.krig.api.data.HlcTimestamp
import space.kscience.krig.storage.journal.InMemoryEventJournal
import space.kscience.krig.storage.journal.SequenceCursor
import space.kscience.dataforge.names.asName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class RecordingReplayLogTest {

    private fun attached(atMs: Long): DeviceMessage = DeviceAttachedMessage(
        time = Instant.fromEpochMilliseconds(atMs),
        deviceName = "child".asName(),
        manifestId = "bp.test".asName(),
        sourceDevice = "lab.hub".asName(),
    )

    @Test
    fun replayFiltersByTimeBounds() = runTest {
        val store = InMemoryEventJournal()
        listOf(100L, 200L, 300L, 400L).forEach { store.record(attached(it).testEnvelope()) }

        val window = store.replay(
            from = Instant.fromEpochMilliseconds(150),
            until = Instant.fromEpochMilliseconds(350),
        ).toList()
        assertEquals(listOf(200L, 300L), window.map { it.payload.time.toEpochMilliseconds() })
    }

    @Test
    fun replaySortsByTimeRegardlessOfArrivalOrder() = runTest {
        val store = InMemoryEventJournal()
        store.record(attached(300).testEnvelope())
        store.record(attached(100).testEnvelope())
        store.record(attached(200).testEnvelope())
        val all = store.replay(
            from = Instant.fromEpochMilliseconds(0),
            until = Instant.fromEpochMilliseconds(1000),
        ).toList()
        // Arrival order is append-only; replay sorts by time ascending.
        assertEquals(listOf(100L, 200L, 300L), all.map { it.payload.time.toEpochMilliseconds() })
    }

    @Test
    fun replayUsesHlcOrderingWhenBothEnvelopesCarryStamps() = runTest {
        val store = InMemoryEventJournal()
        store.record(attached(300).testEnvelope().withHlcStamp(HlcTimestamp(100, 0)))
        store.record(attached(100).testEnvelope().withHlcStamp(HlcTimestamp(300, 0)))
        store.record(attached(200).testEnvelope().withHlcStamp(HlcTimestamp(200, 0)))

        val all = store.replay(
            from = Instant.fromEpochMilliseconds(0),
            until = Instant.fromEpochMilliseconds(1000),
        ).toList()

        assertEquals(listOf(300L, 200L, 100L), all.map { it.payload.time.toEpochMilliseconds() })
    }

    @Test
    fun replayRecordsPreservesCursorPositions() = runTest {
        val store = InMemoryEventJournal()
        listOf(100L, 200L, 300L, 400L).forEach { store.record(attached(it).testEnvelope()) }

        val records = store.replayRecords(
            from = Instant.fromEpochMilliseconds(150),
            until = Instant.fromEpochMilliseconds(350),
        ).toList()

        assertEquals(listOf(200L, 300L), records.map { it.message.time.toEpochMilliseconds() })
        assertEquals(listOf(SequenceCursor(1), SequenceCursor(2)), records.map { it.cursor })
    }

    @Test
    fun sizeReflectsRecordedEventCount() = runTest {
        val store = InMemoryEventJournal()
        assertEquals(0, store.size())
        store.record(attached(1).testEnvelope())
        store.record(attached(2).testEnvelope())
        assertEquals(2, store.size())
    }

    @Test
    fun replayFromCursorRemainsStableAfterEviction() = runTest {
        val store = InMemoryEventJournal(capacity = 3)
        listOf(100L, 200L, 300L).forEach { store.record(attached(it).testEnvelope()) }

        val cursor = store.replayRecords(
            from = Instant.fromEpochMilliseconds(200),
            until = Instant.fromEpochMilliseconds(200),
        ).toList().single().cursor

        store.record(attached(400).testEnvelope())
        store.record(attached(500).testEnvelope())

        val replayed = store.replayFrom(cursor).toList()

        assertEquals(listOf(300L, 400L, 500L), replayed.map { it.message.time.toEpochMilliseconds() })
        assertEquals(listOf(SequenceCursor(2), SequenceCursor(3), SequenceCursor(4)), replayed.map { it.cursor })
    }
}
