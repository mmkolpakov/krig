package space.kscience.controls.api.data

import kotlinx.serialization.Serializable
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * A container for a piece of state, coupling the [value] with its metadata.
 * Represents a snapshot of data on the "Slow Path" (DataForge Integration).
 *
 * @param T The type of the value.
 * @property value The actual data.
 * @property timestamp The time when this value was read or generated.
 * @property quality The detailed quality of this value.
 */
@Serializable
public data class StateValue<out T>(
    val value: T,
    val timestamp: Instant,
    val quality: DataQuality,
) {
    /**
     * Creates a new [StateValue] by applying a mapping function to the value,
     * while preserving timestamp and quality.
     */
    public fun <R> map(mapper: (T) -> R): StateValue<R> = StateValue(
        value = mapper(value),
        timestamp = this.timestamp,
        quality = this.quality
    )

    public companion object {
        /**
         * A helper function to combine two Quality values, returning the "worst" of the two.
         * Priority logic: BAD > WARNING > UNKNOWN > OK.
         *
         * Note: We cannot use .ordinal comparison because UNKNOWN(0) is logically "worse" than OK(1).
         */
        private fun combineQuality(q1: DataQuality, q2: DataQuality): DataQuality {
            return when {
                q1.quality == Quality.BAD || q2.quality == Quality.BAD -> {
                    DataQuality(Quality.BAD, "Combined: ${q1.comment} | ${q2.comment}")
                }
                q1.quality == Quality.WARNING || q2.quality == Quality.WARNING -> {
                    DataQuality(Quality.WARNING, "Combined: ${q1.comment} | ${q2.comment}")
                }
                q1.quality == Quality.UNKNOWN || q2.quality == Quality.UNKNOWN -> {
                    DataQuality(Quality.UNKNOWN, "Combined: ${q1.comment} | ${q2.comment}")
                }
                else -> DataQuality.OK
            }
        }

        /**
         * Creates a new [StateValue] by combining two source states.
         * The timestamp will be the latest of the two.
         * The quality will be the worst of the two.
         */
        public fun <T1, T2, R> combine(
            s1: StateValue<T1>,
            s2: StateValue<T2>,
            mapper: (T1, T2) -> R,
        ): StateValue<R> {
            val newTimestamp = if (s1.timestamp > s2.timestamp) s1.timestamp else s2.timestamp

            return StateValue(
                value = mapper(s1.value, s2.value),
                timestamp = newTimestamp,
                quality = combineQuality(s1.quality, s2.quality)
            )
        }
    }
}

/**
 * Creates a [StateValue] with the current time and [DataQuality.OK].
 */
public fun <T> okState(value: T, clock: Clock = Clock.System): StateValue<T> =
    StateValue(value, clock.now(), DataQuality.OK)