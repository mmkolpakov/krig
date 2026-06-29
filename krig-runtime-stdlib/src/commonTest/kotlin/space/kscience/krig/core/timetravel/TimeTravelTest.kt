package space.kscience.krig.core.timetravel

import kotlinx.coroutines.test.runTest
import space.kscience.krig.api.data.DeviceSnapshot
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.asValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/**
 * End-to-end regression for [Reconstructible.timeTravel]: given a
 * deterministic event log and a baseline snapshot, the fold reaches the
 * expected state at a point-in-time query.
 */
class TimeTravelTest {

    @Test
    fun replayFromScratchReachesFinalValue() = runTest {
        val log = counterReplayLog(counterEvent(100, 1), counterEvent(200, 2), counterEvent(300, 3))
        val replay = CounterReplay()
        replay.timeTravel(at = Instant.fromEpochMilliseconds(300), log = log)
        assertEquals(3, replay.value)
    }

    @Test
    fun pointInTimeStopsAtBoundary() = runTest {
        val log = counterReplayLog(counterEvent(100, 1), counterEvent(200, 2), counterEvent(300, 3))
        val replay = CounterReplay()
        replay.timeTravel(at = Instant.fromEpochMilliseconds(200), log = log)
        assertEquals(2, replay.value, "Events after `at` must not be applied")
    }

    @Test
    fun snapshotBaselineSkipsPriorReplay() = runTest {
        val log = counterReplayLog(counterEvent(100, 1), counterEvent(200, 2), counterEvent(300, 3))
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
        val log = counterReplayLog(counterEvent(100, 1), counterEvent(200, 2), counterEvent(300, 3))
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
