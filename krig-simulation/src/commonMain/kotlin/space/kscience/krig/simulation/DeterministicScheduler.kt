package space.kscience.krig.simulation

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestDispatcher
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.time.TimeSource

/**
 * Deterministic virtual-time [SimulationScheduler] wrapping [TestCoroutineScheduler].
 * Adds [initial time offset][initialTimeMs], a `kotlin.time.Clock` adapter, and a raw
 * [scheduleAt] primitive. Single-threaded, like TCS — one per federate for co-simulation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
public class DeterministicScheduler(
    private val initialTimeMs: Long = 0L,
) : SimulationScheduler {

    /** Underlying test scheduler. Not part of the public API. */
    internal val underlying: TestCoroutineScheduler = TestCoroutineScheduler()

    private val dispatcher: CoroutineDispatcher =
        StandardTestDispatcher(underlying, name = "DeterministicScheduler")

    private val scheduledEvents = ArrayList<ScheduledEvent>()
    private var nextEventSequence: Long = 0L

    override val currentTimeMs: Long
        get() = initialTimeMs + underlying.currentTime

    override suspend fun advanceBy(duration: Duration) {
        val targetTimeMs = currentTimeMs + duration.inWholeMilliseconds.coerceAtLeast(0L)
        drainScheduledEventsUntil(targetTimeMs)
        val remaining = targetTimeMs - currentTimeMs
        if (remaining > 0L) underlying.advanceTimeBy(remaining)
        underlying.runCurrent()
    }

    override fun asDispatcher(): CoroutineDispatcher = dispatcher

    override fun asClock(): Clock = object : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(currentTimeMs)
    }

    override fun asTimeSource(): TimeSource = underlying.timeSource

    public companion object {
        /** Returns `true` for coroutine-test virtual-time dispatchers. */
        public fun isVirtualDispatcher(dispatcher: CoroutineDispatcher): Boolean =
            dispatcher is TestDispatcher
    }

    /** Schedules [runnable] at virtual time [atMs] (offset-adjusted). Past times run on next drain. */
    public fun scheduleAt(atMs: Long, runnable: Runnable) {
        val event = ScheduledEvent(
            atMs = atMs.coerceAtLeast(currentTimeMs),
            sequence = nextEventSequence++,
            runnable = runnable,
        )
        scheduledEvents.add(event)
        siftUp(scheduledEvents.lastIndex)
    }

    private fun drainScheduledEventsUntil(targetTimeMs: Long) {
        while (scheduledEvents.isNotEmpty() && scheduledEvents.first().atMs <= targetTimeMs) {
            val event = removeNextEvent()
            val deltaMs = event.atMs - currentTimeMs
            if (deltaMs > 0L) underlying.advanceTimeBy(deltaMs)
            underlying.runCurrent()
            event.runnable.run()
            underlying.runCurrent()
        }
    }

    private fun removeNextEvent(): ScheduledEvent {
        val first = scheduledEvents.first()
        val last = scheduledEvents.removeAt(scheduledEvents.lastIndex)
        if (scheduledEvents.isNotEmpty()) {
            scheduledEvents[0] = last
            siftDown()
        }
        return first
    }

    private fun siftUp(startIndex: Int) {
        var index = startIndex
        while (index > 0) {
            val parent = index.dec() ushr 1
            if (scheduledEvents[parent] <= scheduledEvents[index]) break
            scheduledEvents.swap(parent, index)
            index = parent
        }
    }

    private fun siftDown() {
        var index = 0
        while (true) {
            val left = index * 2 + 1
            if (left >= scheduledEvents.size) return
            val right = left + 1
            val child = if (right < scheduledEvents.size && scheduledEvents[right] < scheduledEvents[left]) right else left
            if (scheduledEvents[index] <= scheduledEvents[child]) return
            scheduledEvents.swap(index, child)
            index = child
        }
    }
}

private fun <T> MutableList<T>.swap(left: Int, right: Int) {
    val tmp = this[left]
    this[left] = this[right]
    this[right] = tmp
}

private data class ScheduledEvent(
    val atMs: Long,
    val sequence: Long,
    val runnable: Runnable,
) : Comparable<ScheduledEvent> {
    override fun compareTo(other: ScheduledEvent): Int {
        val byTime = atMs.compareTo(other.atMs)
        if (byTime != 0) return byTime
        return sequence.compareTo(other.sequence)
    }
}
