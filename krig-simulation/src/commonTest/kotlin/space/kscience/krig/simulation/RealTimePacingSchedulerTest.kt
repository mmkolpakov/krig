package space.kscience.krig.simulation

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

class RealTimePacingSchedulerTest {

    @Test
    fun advanceMovesVirtualTimeForward() = runTest {
        val scheduler = RealTimePacingScheduler(initialTimeMs = 1_000L)
        assertEquals(1_000L, scheduler.currentTimeMs)

        scheduler.advanceBy(25.milliseconds)

        assertEquals(1_025L, scheduler.currentTimeMs)
        assertEquals(1_025L, scheduler.asClock().now().toEpochMilliseconds())
    }
}
