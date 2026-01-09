package space.kscience.controls.core.state

import kotlinx.atomicfu.AtomicLongArray
import space.kscience.controls.api.spec.DeadbandPolicy
import space.kscience.controls.api.spec.RealtimePolicy
import space.kscience.controls.api.spec.SampledPolicy
import space.kscience.controls.api.spec.TelemetryPolicy
import space.kscience.controls.common.atomics.AtomicDoubleArray
import space.kscience.controls.core.InternalControlsApi
import kotlin.math.abs
import kotlin.time.TimeSource

/**
 * Internal component responsible for Traffic Shaping (Quality of Service) logic.
 *
 * It maintains shadow state (last emitted values and timestamps) to decide
 * whether a new value update warrants a notification emission.
 *
 * @param policies The array of telemetry policies, mapped 1:1 to property token indices.
 */
@InternalControlsApi
public class QoSController(
    private val policies: Array<TelemetryPolicy>
) {
    private val size = policies.size

    // Shadow state for Deadband calculations (Doubles)
    private val lastEmittedDoubles = AtomicDoubleArray(size)

    // Shadow state for Deadband calculations (Longs/Ints/Booleans)
    private val lastEmittedLongs = AtomicLongArray(size)

    // Shadow state for Sampling calculations (Timestamps in epoch millis)
    private val lastEmissionTimes = AtomicLongArray(size)

    init {
        // Initialize doubles to NaN to ensure first value is always emitted
        for (i in 0 until size) {
            lastEmittedDoubles[i] = Double.NaN
        }
    }

    /**
     * Checks if a Double value update should be emitted according to its policy.
     * Updates internal shadow state if emission is allowed.
     *
     * @param index The property token index.
     * @param newValue The new value candidate.
     * @return `true` if the update should be emitted to listeners.
     */
    public fun checkDouble(index: Int, newValue: Double): Boolean {
        return when (val policy = policies[index]) {
            is RealtimePolicy -> true
            is DeadbandPolicy -> {
                val last = lastEmittedDoubles[index]
                // Emit if first value (NaN) or delta exceeded
                if (last.isNaN() || abs(newValue - last) >= policy.delta) {
                    lastEmittedDoubles[index] = newValue
                    true
                } else {
                    false
                }
            }
            is SampledPolicy -> checkSampled(index, policy)
            else -> true // Default to Realtime for unknown policies
        }
    }

    /**
     * Checks if a Long value update should be emitted.
     * Note: Booleans are treated as Longs (0L/1L) for QoS purposes.
     *
     * @param index The property token index.
     * @param newValue The new value candidate.
     * @return `true` if the update should be emitted.
     */
    public fun checkLong(index: Int, newValue: Long): Boolean {
        return when (val policy = policies[index]) {
            is RealtimePolicy -> true
            is DeadbandPolicy -> {
                val last = lastEmittedLongs[index].value
                if (abs(newValue - last) >= policy.delta) {
                    lastEmittedLongs[index].value = newValue
                    true
                } else {
                    false
                }
            }
            is SampledPolicy -> checkSampled(index, policy)
            else -> true
        }
    }

    /**
     * Checks if an Object value update should be emitted.
     * Deadband is not supported for generic objects (equality check might be expensive/undefined),
     * so it falls back to Realtime unless Sampling is specified.
     */
    public fun checkObject(index: Int): Boolean {
        return when (val policy = policies[index]) {
            is SampledPolicy -> checkSampled(index, policy)
            else -> true
        }
    }

    /**
     * Shared logic for SampledPolicy.
     * Uses [TimeSource.Monotonic] for robust time delta calculation independent of system clock changes.
     */
    private fun checkSampled(index: Int, policy: SampledPolicy): Boolean {
        val now = TimeSource.Monotonic.markNow().elapsedNow().inWholeMilliseconds
        val last = lastEmissionTimes[index].value
        return if (now - last >= policy.interval.inWholeMilliseconds) {
            lastEmissionTimes[index].value = now
            true
        } else {
            false
        }
    }
}