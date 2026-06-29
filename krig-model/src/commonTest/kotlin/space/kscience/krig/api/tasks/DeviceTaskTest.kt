package space.kscience.krig.api.tasks

import space.kscience.dataforge.names.asName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeviceTaskTest {

    @Test
    fun progressFractionIsBounded() {
        assertEquals(0.0, DeviceTaskProgress(fraction = 0.0).fraction)
        assertEquals(1.0, DeviceTaskProgress(fraction = 1.0).fraction)

        assertFailsWith<IllegalArgumentException> { DeviceTaskProgress(fraction = -0.1) }
        assertFailsWith<IllegalArgumentException> { DeviceTaskProgress(fraction = 1.1) }
        assertFailsWith<IllegalArgumentException> { DeviceTaskProgress(fraction = Double.NaN) }
    }

    @Test
    fun taskStateReportsTerminalPhases() {
        val running = DeviceTaskState(
            taskId = DeviceTaskId("calibration-1"),
            actionName = "calibration.start".asName(),
            phase = DeviceTaskPhase.Running,
        )
        val done = running.copy(phase = DeviceTaskPhase.Succeeded)

        assertFalse(running.terminal)
        assertTrue(done.terminal)
    }
}
