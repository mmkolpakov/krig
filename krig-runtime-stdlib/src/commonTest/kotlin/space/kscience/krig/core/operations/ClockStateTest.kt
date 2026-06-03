@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package space.kscience.krig.core.operations

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlin.time.TimeMark

private class TestSchedulerClock(
    private val scheduler: TestCoroutineScheduler,
) : Clock {
    override fun now(): Instant = Instant.fromEpochMilliseconds(scheduler.currentTime)
}

private class TestSamplingClock(
    private val scheduler: TestCoroutineScheduler,
) : SamplingClock {
    override fun markNow(): TimeMark {
        val startedAt = scheduler.currentTime
        return object : TimeMark {
            override fun elapsedNow(): Duration =
                (scheduler.currentTime - startedAt).milliseconds
        }
    }

    override suspend fun delay(duration: Duration) {
        kotlinx.coroutines.delay(duration)
    }
}

class ClockStateTest {
    @Test
    fun timerStatePublishesTicksFromInjectedClock() = runTest {
        val clockState = ClockState(
            clock = TestSchedulerClock(testScheduler),
            samplingClock = TestSamplingClock(testScheduler),
        )
        val timer = clockState.timer(this, 10.milliseconds)
        val observed = mutableListOf<Long>()

        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            timer.ticks
                .drop(1)
                .map { it.value.toEpochMilliseconds() }
                .take(2)
                .toList(observed)
        }

        advanceTimeBy(20.milliseconds)
        runCurrent()

        assertEquals(listOf(10L, 20L), observed)
        timer.close()
        job.cancel()
    }
}
