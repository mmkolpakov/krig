package space.kscience.controls.api.data

import kotlinx.serialization.Serializable
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * A generic container representing a snapshot of a property state at a specific point in time.
 *
 * This class belongs to the "Slow Path" (Data Plane). It creates an allocation when instantiated,
 * so it should be used for observers, UI, logging, and RPC, but NOT inside the tight driver loops.
 *
 * @param T The type of the value (e.g., Double, String, Boolean).
 * @property value The actual data.
 * @property timestamp The point in time when this value was generated or read.
 * @property quality The quality assessment of this value.
 */
@Serializable
public data class StateValue<out T>(
    val value: T,
    val timestamp: Instant,
    val quality: DataQuality,
) {
    public companion object {
        /**
         * Creates a [StateValue] with the current system time and [Quality.OK].
         */
        public fun <T> ok(value: T, clock: Clock = Clock.System): StateValue<T> =
            StateValue(value, clock.now(), DataQuality.OK)
    }
}