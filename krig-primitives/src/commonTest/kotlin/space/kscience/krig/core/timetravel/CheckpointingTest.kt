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
import kotlin.coroutines.ContinuationInterceptor
import kotlin.time.Clock
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import space.kscience.krig.api.data.DeviceSnapshot
import space.kscience.krig.api.messages.DeviceMessage
import space.kscience.krig.api.messages.PropertyChangedMessage
import space.kscience.krig.core.contracts.Device
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.asValue
import space.kscience.dataforge.meta.int
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class CheckpointingTest {

    private val source = "lab.counter".asName()

    private class CounterReplay : DeviceReconstructible<Device> {
        var value: Int = 0
            private set

        override suspend fun applyEvent(event: DeviceMessage) {
            val m = event as? PropertyChangedMessage ?: return
            if (m.property == "value".asName()) value = m.value.int ?: value
        }

        override suspend fun captureSnapshot(at: Instant): DeviceSnapshot =
            DeviceSnapshot(at = at, state = Meta(value.asValue()))

        override suspend fun restoreSnapshot(snapshot: DeviceSnapshot) {
            value = snapshot.state.int ?: error("malformed snapshot")
        }
    }

    private fun event(t: Long, v: Int): PropertyChangedMessage = PropertyChangedMessage(
        time = Instant.fromEpochMilliseconds(t),
        sourceDevice = source,
        property = "value".asName(),
        value = Meta(v.asValue()),
    )

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
        val saved = mutableListOf<DeviceSnapshot>()

        override suspend fun save(subject: Name, snapshot: DeviceSnapshot) {
            saved += snapshot
        }

        override suspend fun latestBefore(subject: Name, threshold: Instant): DeviceSnapshot? =
            saved.lastOrNull { it.at <= threshold }

        override suspend fun delete(subject: Name, olderThan: Instant?) {
            saved.removeAll { olderThan == null || it.at < olderThan }
        }
    }

    @Test
    fun everyNEventsStrategyCapturesEveryNthSnapshot() = runTest {
        val counter = CounterReplay()
        val store = InMemorySnapshotStore()
        val scope = detachedScope()
        val devName = "counter".asName()

        // Cold flow: collector subscribes at launchIn and receives every value deterministically.
        val events = (1..4).map { event(100L + it, it) }
        val feed = flowOf(*events.toTypedArray()).onEach { counter.applyEvent(it) }

        val job = counter.runCheckpointing(
            deviceName = devName,
            messageFlow = feed,
            snapshotStore = store,
            strategy = CheckpointStrategy.EveryNEvents(2),
            scope = scope,
            clock = FakeClock(startMs = 500),
        )
        job.join()

        // Expect snapshots at events 2 and 4 (fake clock 500ms, 501ms, ...).
        val latest = store.latestBefore(devName, Instant.fromEpochMilliseconds(9999))
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

        val feed = flowOf(event(1, 42)).onEach { counter.applyEvent(it) }
        val job = counter.runCheckpointing(
            deviceName = devName,
            messageFlow = feed,
            snapshotStore = store,
            strategy = CheckpointStrategy.Manual,
            scope = scope,
        )
        advanceUntilIdle()

        // Manual path writes nothing automatically.
        val latest = store.latestBefore(devName, Instant.fromEpochMilliseconds(1000))
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
            deviceName = devName,
            messageFlow = flowOf(),
            snapshotStore = store,
            strategy = CheckpointStrategy.EveryDuration(10.milliseconds),
            scope = scope,
            clock = FakeClock(startMs = 700),
        )

        advanceTimeBy(10.milliseconds)
        runCurrent()
        assertEquals(1, store.saved.size)

        advanceTimeBy(10.milliseconds)
        runCurrent()
        assertEquals(1, store.saved.size)

        counter.applyEvent(event(1, 7))
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
            CheckpointStrategy.EveryDuration(0.milliseconds).let { }
            error("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // ok
        }
    }

    @Test
    fun everyNEventsRequiresPositiveN() {
        try {
            CheckpointStrategy.EveryNEvents(0).let { }
            error("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // ok
        }
    }
}
