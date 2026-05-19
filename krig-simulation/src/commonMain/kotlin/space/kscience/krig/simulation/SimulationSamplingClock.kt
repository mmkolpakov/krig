package space.kscience.krig.simulation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.withContext
import space.kscience.krig.dsl.SamplingClock
import space.kscience.krig.dsl.fixedRateTicks as runtimeFixedRateTicks
import space.kscience.krig.dsl.sharedTicks as runtimeSharedTicks
import kotlin.time.Duration
import kotlin.time.TimeMark

/** [SamplingClock] backed by this [ClockManager]'s dispatcher and time source. */
public fun ClockManager.asSamplingClock(): SamplingClock = object : SamplingClock {
    override fun markNow(): TimeMark = timeSource.markNow()

    override suspend fun delay(duration: Duration) {
        withContext(simulationDispatcher) {
            kotlinx.coroutines.delay(duration)
        }
    }
}

/** Cold fixed-rate tick stream using [ClockManager.simulationDispatcher]. */
public fun ClockManager.fixedRateTicks(tick: Duration): Flow<Unit> =
    runtimeFixedRateTicks(tick, asSamplingClock())

/** Shared fixed-rate tick stream using [ClockManager.simulationDispatcher]. */
public fun ClockManager.sharedTicks(
    scope: CoroutineScope,
    tick: Duration,
    started: SharingStarted = SharingStarted.WhileSubscribed(),
): SharedFlow<Unit> = runtimeSharedTicks(scope, tick, asSamplingClock(), started)
