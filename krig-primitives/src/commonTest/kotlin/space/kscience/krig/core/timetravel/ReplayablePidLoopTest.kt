package space.kscience.krig.core.timetravel

import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.test.runTest
import space.kscience.krig.api.data.DeviceSnapshot
import space.kscience.krig.api.messages.DeviceMessage
import space.kscience.krig.api.messages.PropertyChangedMessage
import space.kscience.krig.core.contracts.Device
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.asValue
import space.kscience.dataforge.meta.double
import space.kscience.dataforge.meta.get
import space.kscience.dataforge.meta.set
import space.kscience.dataforge.names.asName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * PID-like recording that exercises [timeTravel] over a hand-built event log:
 * forward reconstruction, mid-run rewind, and a truncated counterfactual tail.
 * The same pattern fits any [Reconstructible] device this test just uses a
 * PID as a recognisable shape.
 */
class ReplayablePidLoopTest {

    private val source = "lab.pid".asName()

    private class PidReplay : DeviceReconstructible<Device> {
        var setpoint: Double = 0.0 ; private set
        var processVariable: Double = 0.0 ; private set
        var output: Double = 0.0 ; private set

        override suspend fun applyEvent(event: DeviceMessage) {
            val m = event as? PropertyChangedMessage ?: return
            val v = m.value.double ?: return
            when (m.property.toString()) {
                "setpoint" -> setpoint = v
                "processVariable" -> processVariable = v
                "output" -> output = v
            }
        }

        override suspend fun captureSnapshot(at: Instant): DeviceSnapshot = DeviceSnapshot(
            at = at,
            state = Meta {
                set("output", output)
                set("pv", processVariable)
                set("sp", setpoint)
            },
        )

        override suspend fun restoreSnapshot(snapshot: DeviceSnapshot) {
            output = snapshot.state["output"].double ?: 0.0
            processVariable = snapshot.state["pv"].double ?: 0.0
            setpoint = snapshot.state["sp"].double ?: 0.0
        }
    }

    private fun change(t: Long, property: String, v: Double): PropertyChangedMessage =
        PropertyChangedMessage(
            time = Instant.fromEpochMilliseconds(t),
            property = property.asName(),
            value = Meta(v.asValue()),
            sourceDevice = source,
        )

    @Test
    fun recordedPidRunIsReplayableAtAnyInstant() = runTest {
        // 10-tick run: setpoint = 10.0, PV asymptotes, output decays as error shrinks.
        val events = buildList {
            add(change(0, "setpoint", 10.0))
            var pv = 0.0
            for (i in 1..10) {
                pv += (10.0 - pv) * 0.25
                val output = (10.0 - pv) * 3.0
                add(change(i * 100L, "processVariable", pv))
                add(change(i * 100L, "output", output))
            }
        }
        val log = DeviceEventLog(events.asFlow())

        val replay = PidReplay()
        // Forward to the end of the run.
        replay.timeTravel(at = Instant.fromEpochMilliseconds(1_000), log = log)
        assertEquals(10.0, replay.setpoint)
        assertTrue(replay.processVariable > 9.0, "PV should approach setpoint by t=1000")

        // Rewind to the middle of the rise.
        replay.timeTravel(at = Instant.fromEpochMilliseconds(300), log = log)
        val midPv = replay.processVariable
        assertTrue(midPv in 3.0..7.0, "PV at t=300 should be mid-rise, got $midPv")

        // Counterfactual: truncate the log at t=500; the replay should freeze there.
        val truncated = events.takeWhile { it.time.toEpochMilliseconds() <= 500 }
        val counterfactual = DeviceEventLog(truncated.asFlow())
        replay.timeTravel(at = Instant.fromEpochMilliseconds(1_000), log = counterfactual)
        assertTrue(
            replay.processVariable < 9.0,
            "Truncated log must diverge from the full run � PV should be lower",
        )
    }
}
