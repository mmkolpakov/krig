package space.kscience.krig.simulation

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlin.time.TimeSource

/**
 * Wall-clock-paced [SimulationScheduler] for hardware-in-the-loop runs, where part of the system is
 * real and the virtual part must tick in step with reality rather than as fast as the CPU allows.
 *
 * [advanceBy] moves virtual time forward by the requested step, then throttles: if virtual time has
 * outrun the wall clock it suspends until reality catches up. When computation lags behind real time
 * it does not skip — pacing only slows a too-fast simulation, it never fast-forwards a slow one.
 * Unlike [DeterministicScheduler] this runs on a real dispatcher and a real clock.
 */
public class RealTimePacingScheduler(
    private val realClock: Clock = Clock.System,
    initialTimeMs: Long = realClock.now().toEpochMilliseconds(),
) : SimulationScheduler {

    private val lock = SynchronizedObject()
    private val initialVirtualMs: Long = initialTimeMs
    private val realStartMs: Long = realClock.now().toEpochMilliseconds()
    private var virtualTimeMs: Long = initialTimeMs

    override val currentTimeMs: Long get() = synchronized(lock) { virtualTimeMs }

    override suspend fun advanceBy(duration: Duration) {
        val step = duration.inWholeMilliseconds.coerceAtLeast(0L)
        val target = synchronized(lock) {
            virtualTimeMs += step
            virtualTimeMs
        }
        val elapsedReal = realClock.now().toEpochMilliseconds() - realStartMs
        val aheadOfRealMs = target - initialVirtualMs - elapsedReal
        if (aheadOfRealMs > 0L) delay(aheadOfRealMs.milliseconds)
    }

    override fun asDispatcher(): CoroutineDispatcher = Dispatchers.Default

    override fun asClock(): Clock = object : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(currentTimeMs)
    }

    override fun asTimeSource(): TimeSource = TimeSource.Monotonic
}
