package space.kscience.controls.api.data

import kotlin.time.Clock
import kotlin.time.Instant

/**
 * A container for a piece of state, coupling the [value] with its metadata.
 */
public data class StateValue<out T>(
    val value: T,
    val timestamp: Instant,
    val quality: DataQuality,
) {
    /**
     * Creates a new [StateValue] by applying a mapping function to the value,
     * while preserving the timestamp and quality.
     */
    public fun <R> map(mapper: (T) -> R): StateValue<R> = StateValue(
        value = mapper(value),
        timestamp = this.timestamp,
        quality = this.quality
    )

    public companion object {
        /**
         * A helper function to combine Quality values.
         */
        public fun <T1, T2, R> combine(
            s1: StateValue<T1>,
            s2: StateValue<T2>,
            mapper: (T1, T2) -> R,
        ): StateValue<R> = StateValue(
            value = mapper(s1.value, s2.value),
            timestamp = maxOf(s1.timestamp, s2.timestamp),
            quality = DataQuality.combine(s1.quality, s2.quality)
        )
    }
}

/**
 * Creates a [StateValue] with the current time and [Quality.OK].
 */
public fun <T> okState(value: T, clock: Clock = Clock.System): StateValue<T> = StateValue(value, clock.now(), DataQuality.OK)