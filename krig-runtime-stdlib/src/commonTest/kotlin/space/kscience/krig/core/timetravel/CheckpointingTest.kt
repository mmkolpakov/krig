@file:OptIn(
    ExperimentalCoroutinesApi::class,
    space.kscience.krig.core.ExperimentalKrigApi::class,
)

package space.kscience.krig.core.timetravel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.asFlow
import kotlin.coroutines.ContinuationInterceptor
import kotlin.time.Clock
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import space.kscience.krig.api.messages.DeviceMessage
import space.kscience.dataforge.meta.int
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName
import space.kscience.krig.storage.journal.CheckpointAnchor
import space.kscience.krig.storage.journal.InMemoryEventJournal
import space.kscience.krig.storage.journal.JournalCompactionPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class CheckpointingTest {

    private suspend fun detachedScope(): CoroutineScope {
        val dispatcher = currentCoroutineContext()[ContinuationInterceptor]
            ?: error("Test scope must provide a dispatcher")
        return CoroutineScope(Job() + dispatcher)
    }

    /** Monotonically ticking clock for deterministic snapshot timestamps. */
    private class FakeClock(startMs: Long = 500) : Clock {
        private var now: Instant = Instant.fromEpochMilliseconds(startMs)
        override fun now(): Instant {
            val t = now
            now = Instant.fromEpochMilliseconds(now.toEpochMilliseconds() + 1)
            return t
        }
    }

    private class RecordingSnapshotStore : SnapshotStore {
        val saved = mutableListOf<SnapshotEntry>()

        override suspend fun save(snapshot: SnapshotEntry) {
            saved += snapshot
        }

        override suspend fun latestBefore(subject: Name, threshold: Instant): SnapshotEntry? =
            saved.lastOrNull { it.subject == subject && it.at <= threshold }

        override fun read(subject: Name) =
            saved.filter { it.subject == subject }.asFlow()

        override suspend fun delete(subject: Name, olderThan: Instant?) {
            saved.removeAll { it.subject == subject && (olderThan == null || it.at < olderThan) }
        }
    }

    @Test
    fun everyNEventsStrategyCapturesEveryNthSnapshot() = runTest {
        val counter = CounterReplay()
        val store = InMemorySnapshotStore()
        val scope = detachedScope()
        val devName = "counter".asName()

        // Cold flow: collector subscribes at launchIn and receives every value deterministically.
        val events = (1..4).map { counterEvent(100L + it, it) }
        val feed = flowOf(*events.toTypedArray()).onEach { counter.applyEvent(it) }.testEnvelopes()

        val job = counter.runCheckpointing(
            subject = devName,
            messageFlow = feed,
            snapshotStore = store,
            strategy = CheckpointStrategy.everyNEvents(2),
            scope = scope,
            clock = FakeClock(startMs = 500),
        )
        job.join()

        // Expect snapshots at events 2 and 4 (fake clock 500ms, 501ms, ...).
        val latest = store.latestSnapshotBefore(devName, Instant.fromEpochMilliseconds(9999))
        assertTrue(latest != null, "expected at least one snapshot")
        assertEquals(4, latest.state.int)

        scope.cancel()
    }

    @Test
    fun manualStrategyProducesNoAutomaticSnapshots() = runTest {
        val counter = CounterReplay()
        val store = InMemorySnapshotStore()
        val scope = detachedScope()
        val devName = "counter".asName()

        val feed = flowOf(counterEvent(1, 42)).onEach { counter.applyEvent(it) }.testEnvelopes()
        val job = counter.runCheckpointing(
            subject = devName,
            messageFlow = feed,
            snapshotStore = store,
            strategy = CheckpointStrategy.manual,
            scope = scope,
        )
        advanceUntilIdle()

        // Manual path writes nothing automatically.
        val latest = store.latestSnapshotBefore(devName, Instant.fromEpochMilliseconds(1000))
        assertEquals(null, latest)

        scope.cancel()
        assertTrue(job.isCancelled || job.isCompleted)
    }

    @Test
    fun everyDurationSkipsUnchangedSnapshotContent() = runTest {
        val counter = CounterReplay()
        val store = RecordingSnapshotStore()
        val scope = detachedScope()
        val devName = "counter".asName()

        val job = counter.runCheckpointing(
            subject = devName,
            messageFlow = flowOf<DeviceMessage>().testEnvelopes(),
            snapshotStore = store,
            strategy = CheckpointStrategy.everyDuration(10.milliseconds),
            scope = scope,
            clock = FakeClock(startMs = 700),
        )

        advanceTimeBy(10.milliseconds)
        runCurrent()
        assertEquals(1, store.saved.size)

        advanceTimeBy(10.milliseconds)
        runCurrent()
        assertEquals(1, store.saved.size)

        counter.applyEvent(counterEvent(1, 7))
        advanceTimeBy(10.milliseconds)
        runCurrent()
        assertEquals(2, store.saved.size)
        assertEquals(7, store.saved.last().state.int)

        job.cancel()
        scope.cancel()
    }

    @Test
    fun everyDurationRequiresPositiveDuration() {
        try {
            CheckpointStrategy.everyDuration(0.milliseconds).let { }
            error("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // ok
        }
    }

    @Test
    fun journalCompactionRequiresCheckpointAnchorCursor() = runTest {
        val counter = CounterReplay()
        val store = RecordingSnapshotStore()
        val journal = InMemoryEventJournal()
        val scope = detachedScope()
        val devName = "counter".asName()
        val events = (1..2).map { counterEvent(100L + it, it) }
        events.forEach { journal.write(it) }

        val feed = events.asFlow()
            .onEach { counter.applyEvent(it) }
            .testEnvelopes()
        val job = counter.runCheckpointing(
            subject = devName,
            messageFlow = feed,
            snapshotStore = store,
            strategy = CheckpointStrategy.everyNEvents(2),
            scope = scope,
            clock = FakeClock(startMs = 900),
            journal = journal,
            compactionPolicy = JournalCompactionPolicy.TruncateCoveredCursor,
        )
        job.join()

        assertEquals(2, journal.size())
        assertEquals(null, store.saved.single().anchor)
        scope.cancel()
    }

    @Test
    fun checkpointAnchorCursorAllowsJournalCompaction() = runTest {
        val counter = CounterReplay()
        val store = RecordingSnapshotStore()
        val journal = InMemoryEventJournal()
        val scope = detachedScope()
        val devName = "counter".asName()
        val events = (1..2).map { counterEvent(200L + it, it) }
        val cursors = events.map { journal.write(it) }
        var latestIndex = -1

        val feed = events.asFlow()
            .onEach {
                counter.applyEvent(it)
                latestIndex++
            }
            .testEnvelopes()
        val job = counter.runCheckpointing(
            subject = devName,
            messageFlow = feed,
            snapshotStore = store,
            strategy = CheckpointStrategy.everyNEvents(2),
            scope = scope,
            clock = FakeClock(startMs = 950),
            journal = journal,
            compactionPolicy = JournalCompactionPolicy.TruncateCoveredCursor,
            anchorProvider = { cursors.getOrNull(latestIndex)?.let(::CheckpointAnchor) },
        )
        job.join()

        assertEquals(0, journal.size())
        assertEquals(CheckpointAnchor(cursors.last()), store.saved.single().anchor)
        scope.cancel()
    }

    @Test
    fun everyNEventsRequiresPositiveN() {
        try {
            CheckpointStrategy.everyNEvents(0).let { }
            error("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // ok
        }
    }
}
