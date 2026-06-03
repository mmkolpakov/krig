package space.kscience.krig.assembly

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.merge
import space.kscience.krig.api.data.DefaultQualityPolicy
import space.kscience.krig.api.data.QualityPolicy
import space.kscience.krig.core.operations.ClockState
import space.kscience.krig.core.operations.fixedRateTicks
import space.kscience.krig.core.operations.systemClockState
import kotlin.time.Duration.Companion.milliseconds

/**
 * Integrated acquisition runner: binds a [DataAcquisitionConfiguration] to a single
 * [AcquisitionSourceReader] and quality policy, then drives every declared timer from one
 * [ClockState]. It only orchestrates the already-tested [pollTimer] + [fixedRateTicks] primitives —
 * no new polling logic — so the business surface hides the manual "build ticks, pick reader, merge
 * per-timer flows" wiring.
 */
public class AcquisitionRunner internal constructor(
    internal val config: DataAcquisitionConfiguration,
    internal val reader: AcquisitionSourceReader,
    internal val qualityPolicy: QualityPolicy,
)

/** Binds this configuration to a [reader] and [qualityPolicy], yielding a runnable [AcquisitionRunner]. */
public fun DataAcquisitionConfiguration.runner(
    reader: AcquisitionSourceReader,
    qualityPolicy: QualityPolicy = DefaultQualityPolicy,
): AcquisitionRunner = AcquisitionRunner(this, reader, qualityPolicy)

/**
 * Merges every configured timer into one observation stream. Each timer samples on its own
 * fixed-rate ticks derived from [clockState]'s [samplingClock][ClockState.samplingClock], and
 * observation timestamps come from its [clock][ClockState.clock]. Per-tick emission still preserves
 * each timer's tag order (see [pollTimer]); across timers, emissions interleave as they arrive.
 */
public fun AcquisitionRunner.observations(
    clockState: ClockState = systemClockState(),
): Flow<SamplingObservation<AcquisitionTagSpec>> =
    config.timers.map { timer ->
        config.pollTimer(
            timerId = timer.id,
            ticks = fixedRateTicks(timer.intervalMs.milliseconds, clockState.samplingClock),
            reader = reader,
            clock = clockState.clock,
            qualityPolicy = qualityPolicy,
        )
    }.merge()
