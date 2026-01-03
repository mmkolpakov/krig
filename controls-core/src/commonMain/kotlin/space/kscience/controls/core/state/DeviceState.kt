package space.kscience.controls.core.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*
import space.kscience.controls.api.data.Quality
import space.kscience.controls.api.data.StateValue
import space.kscience.controls.api.data.okState
import kotlin.reflect.KProperty
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * An observable, timestamped, and quality-aware state of a device property.
 * This is a read-only view of a state. The canonical representation of the state is [stateValue],
 * which holds a potentially null value along with its metadata.
 *
 * @param T The type of the state value.
 */
public interface DeviceState<out T> {
    /**
     * The full state representation including a nullable value, timestamp, and quality.
     * This is the source of truth for the state.
     */
    public val stateValue: StateValue<T?>

    /**
     * A [Flow] of [StateValue] updates. Emits a new item whenever the value, timestamp, or quality changes.
     */
    public val stateFlow: Flow<StateValue<T?>>

    /**
     * A [Flow] that emits only the value part of the state, whenever it changes.
     */
    public val valueFlow: Flow<T?> get() = stateFlow.map { it.value }
}

/** The nullable value of the state. Accessing this is a shortcut for `stateValue.value`. */
public val <T> DeviceState<T>.value: T? get() = stateValue.value

/**
 * Returns the value of the state, throwing an [IllegalStateException] if the value is null.
 * Use this when the value is expected to be present.
 */
public fun <T> DeviceState<T>.requireValue(): T = stateValue.value ?: error("DeviceState value is null")

/** The timestamp of the last state update. Accessing this is a shortcut for `stateValue.timestamp`. */
public val <T> DeviceState<T>.timestamp: Instant get() = stateValue.timestamp

/** The quality of the current state value. Accessing this is a shortcut for `stateValue.quality`. */
public val <T> DeviceState<T>.quality: Quality get() = stateValue.quality

/**
 * A mutable state of a device property, allowing for updates.
 *
 * @param T The type of the state value.
 */
public interface MutableDeviceState<T> : DeviceState<T> {
    /**
     * Updates the state with a new value, automatically setting the timestamp to now and quality to [Quality.OK].
     * @param value The new value to set.
     */
    public suspend fun update(value: T)

    /**
     * Updates the entire state with a new [StateValue], allowing for explicit control over value, timestamp, and quality.
     * This is useful for scenarios like propagating an error state or a null value.
     * @param stateValue The new full state value.
     */
    public suspend fun updateState(stateValue: StateValue<T?>)

    /**
     * A convenience function to update only the quality of the state, preserving the current value and timestamp.
     * Useful for marking a value as stale or invalid without changing it.
     * @param quality The new quality to set.
     * @param message An optional message, typically used for [Quality.ERROR].
     */
    public suspend fun updateQuality(quality: Quality, message: String? = null)
}

/**
 * A [DeviceState] that explicitly declares its dependencies on other states.
 * This is primarily used for derived/computed states.
 *
 * @param T The type of the state value.
 */
public interface DeviceStateWithDependencies<T> : DeviceState<T> {
    public val dependencies: Collection<DeviceState<*>>
}

/**
 * Property delegate provider to get a state's nullable value.
 */
public operator fun <T> DeviceState<T>.getValue(thisRef: Any?, property: KProperty<*>): T? = value

/**
 * Creates a new read-only [DeviceState] that mirrors the receiver state by mapping its value with [mapper].
 * @param mapper A function to transform the state's value. The input to the mapper is nullable.
 * @return A new [DeviceStateWithDependencies] instance.
 */
public fun <T, R> DeviceState<T>.map(mapper: (T?) -> R?): DeviceStateWithDependencies<R> =
    object : DeviceStateWithDependencies<R> {
        override val dependencies: Collection<DeviceState<*>> = listOf(this@map)
        override val stateValue: StateValue<R?> get() = this@map.stateValue.map(mapper)
        override val stateFlow: Flow<StateValue<R?>> = this@map.stateFlow.map { it.map(mapper) }
    }

/**
 * Combines two device states into a single read-only [DeviceState].
 * The new state's value is computed by applying the [mapper] function to the latest values of both source states.
 *
 * @param other The second [DeviceState] to combine with.
 * @param mapper A function to combine the nullable values of the two states.
 * @return A new [DeviceStateWithDependencies] instance representing the combined state.
 */
public fun <T1, T2, R> DeviceState<T1>.combine(
    other: DeviceState<T2>,
    mapper: (T1?, T2?) -> R?,
): DeviceStateWithDependencies<R> = object : DeviceStateWithDependencies<R> {
    override val dependencies: Collection<DeviceState<*>> = listOf(this@combine, other)
    override val stateValue: StateValue<R?> get() = StateValue.combine(this@combine.stateValue, other.stateValue, mapper)
    override val stateFlow: Flow<StateValue<R?>> = combine(
        this@combine.stateFlow,
        other.stateFlow
    ) { s1, s2 ->
        StateValue.combine(s1, s2, mapper)
    }
}

/**
 * Converts a regular [Flow] of values into a [DeviceState].
 * Each value emitted by the flow becomes the new state's value, with the current time as its timestamp and [Quality.OK].
 *
 * @param scope The [CoroutineScope] in which the state collection will be active.
 * @param initialValue The initial value of the state before the flow emits its first item.
 * @param clock The clock to use for timestamping state changes.
 * @return A [DeviceState] that reflects the values from the flow.
 */
public fun <T> Flow<T>.asDeviceState(
    scope: CoroutineScope,
    initialValue: T,
    clock: Clock = Clock.System,
): DeviceState<T> {
    val stateFlow: StateFlow<StateValue<T?>> = this.map { okState(it, clock) }.stateIn(
        scope,
        SharingStarted.Lazily,
        okState(initialValue, clock)
    )
    return object : DeviceState<T> {
        override val stateValue: StateValue<T?> get() = stateFlow.value
        override val stateFlow: StateFlow<StateValue<T?>> get() = stateFlow
    }
}