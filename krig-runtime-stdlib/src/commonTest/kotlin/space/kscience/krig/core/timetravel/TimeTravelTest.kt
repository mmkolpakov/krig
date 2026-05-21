package space.kscience.krig.core.timetravel

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import space.kscience.krig.api.data.DeviceSnapshot
import space.kscience.krig.api.messages.DeviceMessage
import space.kscience.krig.api.messages.PropertyChangedMessage
import space.kscience.krig.core.contracts.Device
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.asValue
import space.kscience.dataforge.meta.int
import space.kscience.dataforge.names.asName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/**
 * End-to-end regression for [Reconstructible.timeTravel]: given a
 * deterministic event log and a baseline snapshot, the fold reaches the
 * expected state at a point-in-time query.
 */
class TimeTravelTest {

    private val source = "lab.counter".asName()

    /** Minimal reconstructible stand-in: a single mutable counter. */
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
        property = "value".asName(),
        value = Meta(v.asValue()),
        sourceDevice = source,
    )

    @Test
    fun replayFromScratchReachesFinalValue() = runTest {
        val log = ReplayLog(flowOf(event(100, 1), event(200, 2), event(300, 3)))
        val replay = CounterReplay()
        replay.timeTravel(at = Instant.fromEpochMilliseconds(300), log = log)
        assertEquals(3, replay.value)
    }

    @Test
    fun pointInTimeStopsAtBoundary() = runTest {
        val log = ReplayLog(flowOf(event(100, 1), event(200, 2), event(300, 3)))
        val replay = CounterReplay()
        replay.timeTravel(at = Instant.fromEpochMilliseconds(200), log = log)
        assertEquals(2, replay.value, "Events after `at` must not be applied")
    }

    @Test
    fun snapshotBaselineSkipsPriorReplay() = runTest {
        val log = ReplayLog(flowOf(event(100, 1), event(200, 2), event(300, 3)))
        val baseline = DeviceSnapshot(
            at = Instant.fromEpochMilliseconds(200),
            state = Meta(2.asValue()),
        )
        val replay = CounterReplay()
        replay.timeTravel(
            at = Instant.fromEpochMilliseconds(300),
            log = log,
            snapshot = baseline,
        )
        assertEquals(3, replay.value, "Snapshot restores then replays only the delta")
    }

    @Test
    fun reverseTravelRewindsState() = runTest {
        val log = ReplayLog(flowOf(event(100, 1), event(200, 2), event(300, 3)))
        val replay = CounterReplay()
        // Forward to t=300 > value=3.
        replay.timeTravel(at = Instant.fromEpochMilliseconds(300), log = log)
        assertEquals(3, replay.value)
        // Reverse to t=150 reset and fold the prefix. Passing `null` snapshot
        // forces a full replay from DISTANT_PAST, which is exactly the classical
        // Time-Warp "cancel, fold-again" form.
        replay.timeTravel(at = Instant.fromEpochMilliseconds(150), log = log)
        assertEquals(1, replay.value, "Reverse-time query must reach the earlier state")
    }
}
