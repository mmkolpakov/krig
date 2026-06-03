@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package space.kscience.krig.core.operations

import app.cash.turbine.test
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import space.kscience.krig.api.data.DataQuality
import space.kscience.krig.api.data.ObservedValue
import space.kscience.krig.api.data.QualitySeverity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlin.time.TimeMark

private class SchedulerSamplingClock(
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

private class LaggingSamplingClock(
    private val scheduler: TestCoroutineScheduler,
    private val lag: Duration,
) : SamplingClock {
    private var overshoot: Duration = Duration.ZERO

    override fun markNow(): TimeMark {
        val startedAt = scheduler.currentTime
        return object : TimeMark {
            override fun elapsedNow(): Duration =
                (scheduler.currentTime - startedAt).milliseconds + overshoot
        }
    }

    override suspend fun delay(duration: Duration) {
        kotlinx.coroutines.delay(duration)
        overshoot += lag
    }
}

class SampleWithHoldTest {
    @Test
    fun sampleWithHoldRejectsNonPositiveTick() = runTest {
        assertFailsWith<IllegalArgumentException> {
            flowOf(1).sampleWithHold(Duration.ZERO).take(1).collect {}
        }
    }

    @Test
    fun fixedRateTicksUseProvidedClock() = runTest {
        val emittedAt = mutableListOf<Long>()

        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            fixedRateTicks(10.milliseconds, SchedulerSamplingClock(testScheduler))
                .take(3)
                .collect { emittedAt += testScheduler.currentTime }
        }
        advanceTimeBy(30.milliseconds)
        runCurrent()

        assertEquals(listOf(10L, 20L, 30L), emittedAt)
        job.cancel()
    }

    @Test
    fun sharedTicksDriveMultipleSampleWithHoldStreams() = runTest {
        val first = MutableSharedFlow<Int>(replay = 1)
        val second = MutableSharedFlow<Int>(replay = 1)
        first.emit(1)
        second.emit(10)
        val ticks = sharedTicks(backgroundScope, 10.milliseconds, SchedulerSamplingClock(testScheduler))
        val firstValues = mutableListOf<Int>()
        val secondValues = mutableListOf<Int>()

        val firstJob = launch(start = CoroutineStart.UNDISPATCHED) {
            first.sampleWithHold(ticks).take(2).toList(firstValues)
        }
        val secondJob = launch(start = CoroutineStart.UNDISPATCHED) {
            second.sampleWithHold(ticks).take(2).toList(secondValues)
        }
        runCurrent()
        advanceTimeBy(10.milliseconds)
        runCurrent()
        first.emit(2)
        second.emit(20)
        advanceTimeBy(10.milliseconds)
        runCurrent()

        assertEquals(listOf(1, 2), firstValues)
        assertEquals(listOf(10, 20), secondValues)
        firstJob.cancel()
        secondJob.cancel()
    }

    @Test
    fun sampleWithHoldCompletesWhenFiniteUpstreamCompletes() = runTest {
        val values = flowOf(1)
            .sampleWithHold(10.milliseconds, SchedulerSamplingClock(testScheduler))
            .toList()

        assertEquals(emptyList(), values)
    }

    @Test
    fun sampleWithHoldPropagatesUpstreamFailure() = runTest {
        assertFailsWith<IllegalStateException> {
            flow {
                emit(1)
                throw IllegalStateException("boom")
            }.sampleWithHold(10.milliseconds, SchedulerSamplingClock(testScheduler)).toList()
        }
    }

    @Test
    fun sampleWithHoldRepeatsNullableLatestValue() = runTest {
        val source = MutableSharedFlow<String?>(replay = 1)
        source.emit(null)
        val values = mutableListOf<String?>()

        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            source.sampleWithHold(10.milliseconds, SchedulerSamplingClock(testScheduler))
                .take(2)
                .collect { values += it }
        }
        advanceTimeBy(10.milliseconds)
        runCurrent()
        advanceTimeBy(10.milliseconds)
        runCurrent()

        val expected: List<String?> = listOf(null, null)
        assertEquals(expected, values)
        job.cancel()
    }

    @Test
    fun sampleWithHoldSkipsMissedTicksWithoutPhaseDrift() = runTest {
        val source = MutableSharedFlow<Int>(replay = 1)
        source.emit(7)
        val emittedAt = mutableListOf<Long>()

        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            source.sampleWithHold(10.milliseconds, SchedulerSamplingClock(testScheduler))
                .take(3)
                .collect {
                    emittedAt += testScheduler.currentTime
                    delay(15.milliseconds)
                }
        }
        advanceTimeBy(10.milliseconds)
        runCurrent()
        advanceTimeBy(15.milliseconds)
        runCurrent()
        advanceTimeBy(5.milliseconds)
        runCurrent()
        advanceTimeBy(15.milliseconds)
        runCurrent()
        advanceTimeBy(5.milliseconds)
        runCurrent()

        assertEquals(listOf(10L, 30L, 50L), emittedAt)
        job.cancel()
    }

    @Test
    fun sampleWithHoldEmitsWhenDispatcherResumesAfterTickDeadline() = runTest {
        val source = MutableSharedFlow<Int>(replay = 1)
        source.emit(7)
        val values = mutableListOf<Int>()

        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            source.sampleWithHold(10.milliseconds, LaggingSamplingClock(testScheduler, 1.milliseconds))
                .take(1)
                .toList(values)
        }
        advanceTimeBy(10.milliseconds)
        runCurrent()

        assertEquals(listOf(7), values)
        job.cancel()
    }

    @Test
    fun sampleWithHoldUsesExternalTicks() = runTest {
        val source = MutableSharedFlow<Int>(replay = 1)
        val ticks = MutableSharedFlow<Unit>()
        source.emit(1)

        source.sampleWithHold(ticks).test {
            ticks.emit(Unit)
            assertEquals(1, awaitItem())

            source.emit(2)
            ticks.emit(Unit)
            assertEquals(2, awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun withStalenessFallbackEmitsUncertainLastValueOnCompletion() = runTest {
        val values = flowOf(
            ObservedValue(
                value = 42,
                time = Instant.fromEpochMilliseconds(1),
                quality = DataQuality.GOOD,
            )
        ).withStalenessFallback().toList()

        assertEquals(2, values.size)
        assertEquals(42, values[1].value)
        assertEquals(QualitySeverity.UNCERTAIN, values[1].quality.severity)
    }
}
