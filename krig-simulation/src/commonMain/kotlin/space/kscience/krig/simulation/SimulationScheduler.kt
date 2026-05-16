package space.kscience.krig.simulation

import kotlinx.coroutines.CoroutineDispatcher
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * Time advancement for simulations, digital twins, and co-simulation. Default is
 * [DeterministicScheduler]; HIL and federated implementations plug in here.
 */
public interface SimulationScheduler {
    public val currentTimeMs: Long

    /** Runs all tasks scheduled before `currentTimeMs + duration` in order, then returns. */
    public suspend fun advanceBy(duration: Duration)

    public fun asDispatcher(): CoroutineDispatcher

    public fun asClock(): Clock

    public fun asTimeSource(): TimeSource
}
