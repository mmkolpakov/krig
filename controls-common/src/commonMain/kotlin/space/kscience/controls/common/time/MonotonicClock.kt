package space.kscience.controls.common.time

import kotlin.time.TimeSource

/**
 * Utilities for high-precision, allocation-free time measurements needed for QoS (Quality of Service).
 */
public object FastTime {
    private val mark = TimeSource.Monotonic.markNow()

    /**
     * Returns the elapsed time in milliseconds since an arbitrary epoch (application start).
     * Faster than creating Instant objects. Used for [SampledPolicy] checks.
     */
    public fun nowMilliseconds(): Long = mark.elapsedNow().inWholeMilliseconds
}