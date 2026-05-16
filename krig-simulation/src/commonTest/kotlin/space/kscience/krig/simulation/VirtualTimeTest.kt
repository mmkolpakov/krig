package space.kscience.krig.simulation

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import space.kscience.dataforge.context.*
import space.kscience.dataforge.meta.*
import space.kscience.dataforge.names.asName
import kotlin.coroutines.ContinuationInterceptor
import kotlin.test.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

//all passed
/**
 * A test-specific implementation of Clock that reads virtual time directly from a TestScope.
 * This is the key to synchronizing the plugin's clock with the test's virtual time.
 */
private class TestCoroutineClock(
    private val testScope: TestScope,
    private val startTime: Instant,
) : Clock {
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun now(): Instant = startTime + testScope.currentTime.milliseconds
}

/**
 * A test-specific factory for ClockManager that injects the TestScope's dispatcher and clock.
 */
private class TestClockManagerFactory(private val scope: CoroutineScope) : PluginFactory<ClockManager> {
    override val tag: PluginTag get() = ClockManager.tag

    override fun build(context: Context, meta: Meta): ClockManager {
        return object : ClockManager(meta) {
            /**
             * Override the dispatcher to use the one from the runTest scope.
             */
            override val simulationDispatcher: CoroutineDispatcher by lazy {
                when (clockMode) {
                    is ClockMode.Virtual -> scope.coroutineContext[ContinuationInterceptor] as CoroutineDispatcher
                    else -> super.simulationDispatcher
                }
            }

            /**
             * Override the clock to use our TestCoroutineClock.
             */
            override val clock: Clock by lazy {
                when (clockMode) {
                    is ClockMode.Virtual -> {
                        val testScope = scope as? TestScope
                            ?: error("TestClockManagerFactory must be used within a TestScope (from `runTest`).")
                        // Use a reliable way to parse the start time from meta
                        val startTime = meta["clock.start"]?.string?.let { Instant.parse(it) }
                            ?: error("Start time is not defined in meta for virtual clock.")
                        TestCoroutineClock(testScope, startTime)
                    }
                    else -> super.clock
                }
            }
        }
    }
}

/**
 * A test-specific DSL helper to configure a context for virtual time within a `runTest` block.
 */
private fun ContextBuilder.withTestVirtualTime(scope: CoroutineScope, start: Instant) {
    // Register our test factory, which is aware of the test scope.
    plugin(TestClockManagerFactory(scope)) {
        "clock".asName() put {
            "mode" put "virtual"
            "start" put start.toString() // Ensure start time is correctly stored as a string
        }
    }
}


@OptIn(ExperimentalCoroutinesApi::class)
class VirtualTimeTest {

    private val testStartInstant = Instant.parse("2025-01-01T00:00:00Z")

    @Test
    fun testContextWithVirtualTime() = runTest {
        val context = Context("simulationContext") {
            withTestVirtualTime(this@runTest, testStartInstant)
        }

        try {
            val clockManager = context.plugins.get<ClockManager>()
                ?: fail("ClockManager plugin is not loaded.")

            val virtualDispatcher = clockManager.simulationDispatcher
            val virtualClock = clockManager.clock

            assertIs<TestDispatcher>(virtualDispatcher, "The dispatcher should be a TestDispatcher.")
            assertEquals(testStartInstant, virtualClock.now(), "The clock should start at the specified instant.")

            val job = launch {
                delay(5.seconds)
            }

            advanceTimeBy(10.seconds)
            runCurrent() // Ensure all pending tasks at the current time are executed

            assertTrue(job.isCompleted, "Job should be completed after advancing time.")

            val expectedTime = testStartInstant + 10.seconds
            assertEquals(expectedTime, virtualClock.now(), "The virtual clock should advance with the dispatcher.")

        } finally {
            context.close()
        }
    }

    @Test
    fun testSimulatedDeviceBehavior() = runTest {
        val eventLog = mutableListOf<Pair<Instant, String>>()

        val context = Context("simulation") {
            withTestVirtualTime(this@runTest, testStartInstant)
        }

        try {
            val clockManager = context.plugins.get<ClockManager>()
                ?: fail("ClockManager plugin is not loaded.")

            val virtualClock = clockManager.clock

            val deviceJob = launch {
                repeat(3) { i ->
                    eventLog.add(virtualClock.now() to "Tick $i")
                    delay(1.seconds)
                }
            }

            assertEquals(0, currentTime)
            assertTrue(eventLog.isEmpty())

            advanceUntilIdle()

            val expectedLog = listOf(
                testStartInstant to "Tick 0",
                testStartInstant + 1.seconds to "Tick 1",
                testStartInstant + 2.seconds to "Tick 2"
            )
            assertEquals(expectedLog, eventLog)
            assertEquals(3000, currentTime)
            assertTrue(deviceJob.isCompleted)
        } finally {
            context.close()
        }
    }

    @Test
    fun testDispatcherCancellation() = runTest {
        val context = Context("cancellationTest") {
            withTestVirtualTime(this@runTest, testStartInstant)
        }

        try {
            val contextJob = context.coroutineContext.job

            assertTrue(this.coroutineContext.job.isActive, "TestScope's job should be active initially.")
            assertTrue(contextJob.isActive, "Context's job should be active initially.")

            context.close()

            contextJob.cancel("Manual cancellation for test")

            advanceTimeBy(100)
            runCurrent()

            assertFalse(contextJob.isActive, "Context's job should be inactive after manual cancellation.")
            assertTrue(this.coroutineContext.job.isActive, "TestScope's job should remain active.")

        } finally {
            if (context.isActive) context.close()
        }
    }

    @Test
    fun deterministicSchedulerRunsScheduledCallbacksInVirtualTimeOrder() = runTest {
        val scheduler = DeterministicScheduler(initialTimeMs = 1_000)
        val events = mutableListOf<Pair<Long, String>>()

        scheduler.scheduleAt(1_030) { events += scheduler.currentTimeMs to "late" }
        scheduler.scheduleAt(1_010) {
            events += scheduler.currentTimeMs to "first"
            scheduler.scheduleAt(1_010) { events += scheduler.currentTimeMs to "same-time-child" }
        }
        scheduler.scheduleAt(900) { events += scheduler.currentTimeMs to "past" }

        scheduler.advanceBy(30.milliseconds)

        assertEquals(
            listOf(
                1_000L to "past",
                1_010L to "first",
                1_010L to "same-time-child",
                1_030L to "late",
            ),
            events,
        )
    }
}
