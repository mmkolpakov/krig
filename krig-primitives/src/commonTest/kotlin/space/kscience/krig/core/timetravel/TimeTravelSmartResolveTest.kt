@file:OptIn(space.kscience.krig.core.ExperimentalKrigApi::class)

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
 * Validates the `SnapshotStore`-aware [timeTravel] overload: finds the nearest snapshot,
 * replays only the delta, and falls back to full replay when no snapshot exists.
 */
class TimeTravelSmartResolveTest {

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

    @Test
    fun smartResolveLocatesNearestSnapshotAndReplaysOnlyDelta() = runTest {
        val counter = CounterReplay()
        val log = DeviceEventLog(flowOf(event(100, 1), event(200, 2), event(300, 3), event(400, 4)))
        val store = InMemorySnapshotStore()
        val devName = "counter".asName()
        store.save(devName, DeviceSnapshot(at = Instant.fromEpochMilliseconds(200), state = Meta(99.asValue())))

        counter.timeTravel(
            at = Instant.fromEpochMilliseconds(350),
            log = log,
            deviceName = devName,
            snapshotStore = store,
        )

        // Baseline restored from snapshot (value=99), then only events at t=300 applied.
        assertEquals(3, counter.value)
    }

    @Test
    fun smartResolveFallsBackToFullReplayWhenNoSnapshotExists() = runTest {
        val counter = CounterReplay()
        val log = DeviceEventLog(flowOf(event(100, 1), event(200, 2), event(300, 3)))
        val store = InMemorySnapshotStore()
        val devName = "counter".asName()

        counter.timeTravel(
            at = Instant.fromEpochMilliseconds(250),
            log = log,
            deviceName = devName,
            snapshotStore = store,
        )

        // No snapshot > DISTANT_PAST > events up to 200ms applied.
        assertEquals(2, counter.value)
    }
}
