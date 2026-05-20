@file:OptIn(space.kscience.krig.core.ExperimentalKrigApi::class)

package space.kscience.krig.core.timetravel

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import space.kscience.krig.api.messages.DeviceAttachedMessage
import space.kscience.krig.api.messages.DeviceMessage
import space.kscience.dataforge.names.asName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class EventLogStoreTest {

    private fun attached(atMs: Long): DeviceMessage = DeviceAttachedMessage(
        time = Instant.fromEpochMilliseconds(atMs),
        deviceName = "child".asName(),
        blueprintId = "bp.test".asName(),
        sourceDevice = "lab.hub".asName(),
    )

    @Test
    fun replayFiltersByTimeBounds() = runTest {
        val store = InMemoryEventLogStore()
        listOf(100L, 200L, 300L, 400L).forEach { store.record(attached(it)) }

        val window = store.replay(
            from = Instant.fromEpochMilliseconds(150),
            until = Instant.fromEpochMilliseconds(350),
        ).toList()
        assertEquals(listOf(200L, 300L), window.map { it.time.toEpochMilliseconds() })
    }

    @Test
    fun replaySortsByTimeRegardlessOfArrivalOrder() = runTest {
        val store = InMemoryEventLogStore()
        store.record(attached(300))
        store.record(attached(100))
        store.record(attached(200))
        val all = store.replay(
            from = Instant.fromEpochMilliseconds(0),
            until = Instant.fromEpochMilliseconds(1000),
        ).toList()
        // Arrival order is append-only; replay sorts by time ascending.
        assertEquals(listOf(100L, 200L, 300L), all.map { it.time.toEpochMilliseconds() })
    }

    @Test
    fun replayRecordsPreservesCursorPositions() = runTest {
        val store = InMemoryEventLogStore()
        listOf(100L, 200L, 300L, 400L).forEach { store.record(attached(it)) }

        val records = store.replayRecords(
            from = Instant.fromEpochMilliseconds(150),
            until = Instant.fromEpochMilliseconds(350),
        ).toList()

        assertEquals(listOf(200L, 300L), records.map { it.message.time.toEpochMilliseconds() })
        assertEquals(listOf(SequenceCursor(1), SequenceCursor(2)), records.map { it.cursor })
    }

    @Test
    fun sizeReflectsRecordedEventCount() = runTest {
        val store = InMemoryEventLogStore()
        assertEquals(0, store.size())
        store.record(attached(1))
        store.record(attached(2))
        assertEquals(2, store.size())
    }

    @Test
    fun replayFromCursorRemainsStableAfterEviction() = runTest {
        val store = InMemoryEventLogStore(capacity = 3)
        listOf(100L, 200L, 300L).forEach { store.record(attached(it)) }

        val cursor = store.replayRecords(
            from = Instant.fromEpochMilliseconds(200),
            until = Instant.fromEpochMilliseconds(200),
        ).toList().single().cursor

        store.record(attached(400))
        store.record(attached(500))

        val replayed = store.replayFrom(cursor).toList()

        assertEquals(listOf(300L, 400L, 500L), replayed.map { it.message.time.toEpochMilliseconds() })
        assertEquals(listOf(SequenceCursor(2), SequenceCursor(3), SequenceCursor(4)), replayed.map { it.cursor })
    }
}
