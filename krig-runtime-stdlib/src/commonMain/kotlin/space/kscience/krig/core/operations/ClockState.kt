package space.kscience.krig.core.operations

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import space.kscience.krig.api.data.DataQuality
import space.kscience.krig.api.data.ObservedValue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Explicit time source for polling and timer-driven state.
 *
 * [clock] provides timestamps, [samplingClock] provides delay/elapsed scheduling. Production
 * code usually uses [systemClockState]; simulation passes both from ClockManager.
 */
public class ClockState(
    public val clock: Clock = Clock.System,
    public val samplingClock: SamplingClock = monotonicSamplingClock(),
) {
    /** Cold fixed-rate observation stream. Every collector owns its ticker. */
    public fun ticks(
        interval: Duration,
        quality: DataQuality = DataQuality.GOOD,
    ): Flow<ObservedValue<Instant>> = fixedRateTicks(interval, samplingClock).map {
        val now = clock.now()
        ObservedValue(now, now, quality)
    }

    /** Creates a hot timer state bound to [scope]. */
    public fun timer(
        scope: CoroutineScope,
        interval: Duration,
        quality: DataQuality = DataQuality.GOOD,
    ): TimerState = TimerState(scope = scope, interval = interval, clockState = this, quality = quality)
}

/** Production clock state backed by system wall-clock time and monotonic delays. */
public fun systemClockState(): ClockState = ClockState()

/**
 * Hot fixed-rate timer state.
 *
 * It exposes the latest tick as [StateFlow], making timer-driven polling transparent in tests
 * and demos without introducing a separate acquisition runtime abstraction.
 */
public class TimerState(
    scope: CoroutineScope,
    public val interval: Duration,
    public val clockState: ClockState = systemClockState(),
    public val quality: DataQuality = DataQuality.GOOD,
) : AutoCloseable {
    private val mutableTicks: MutableStateFlow<ObservedValue<Instant>> =
        MutableStateFlow(ObservedValue(clockState.clock.now(), clockState.clock.now(), quality))

    private val job: Job = scope.launch {
        clockState.ticks(interval, quality).collect { tick ->
            mutableTicks.value = tick
        }
    }

    /** Latest observed timer tick. */
    public val latest: ObservedValue<Instant> get() = mutableTicks.value

    /** Hot stream of timer ticks, replaying the latest tick to new subscribers. */
    public val ticks: StateFlow<ObservedValue<Instant>> = mutableTicks.asStateFlow()

    override fun close() {
        job.cancel()
    }
}
