package space.kscience.krig.core.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import space.kscience.krig.api.data.DataQuality
import space.kscience.krig.api.data.ObservedValue
import space.kscience.krig.api.data.observed
import kotlin.reflect.KProperty
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * An observable, quality-aware state of a device property.
 * [stateValue] holds the current value, timestamp, and quality.
 *
 * @param T The type of the state value.
 */
public interface DeviceState<out T> {
    /** The full state: nullable value, timestamp, and data quality. */
    public val stateValue: ObservedValue<T?>

    /** Emits a new item whenever the value, timestamp, or quality changes. */
    public val stateFlow: Flow<ObservedValue<T?>>

    /** Emits only the value part of the state on each change. */
    public val valueFlow: Flow<T?> get() = stateFlow.map { it.value }
}

/** The nullable value of the state. Shortcut for `stateValue.value`. */
public val <T> DeviceState<T>.value: T? get() = stateValue.value

/**
 * Returns the value of the state, throwing [IllegalStateException] if null.
 */
public fun <T> DeviceState<T>.requireValue(): T = stateValue.value ?: error("DeviceState value is null")

/** The timestamp of the last state update. Shortcut for `stateValue.time`. */
public val <T> DeviceState<T>.timestamp: Instant get() = stateValue.time

/**
 * A mutable state of a device property, allowing for updates.
 *
 * @param T The type of the state value.
 */
public interface MutableDeviceState<T> : DeviceState<T> {
    /** Updates the state with a new value, setting the timestamp to now. */
    public suspend fun update(value: T, quality: DataQuality = DataQuality.GOOD)

    /** Updates the entire state, allowing explicit control over value, timestamp, and quality. */
    public suspend fun updateState(stateValue: ObservedValue<T?>)
}

/**
 * A [DeviceState] that explicitly declares its dependencies on other states.
 * Used for derived/computed states.
 */
public interface DeviceStateWithDependencies<T> : DeviceState<T> {
    public val dependencies: Collection<DeviceState<*>>
}

/** Property delegate to get the state's nullable value. */
public operator fun <T> DeviceState<T>.getValue(thisRef: Any?, property: KProperty<*>): T? = value

/**
 * Creates a read-only [DeviceState] that maps this state's value with [mapper].
 */
public fun <T, R> DeviceState<T>.map(mapper: (T?) -> R?): DeviceStateWithDependencies<R> =
    object : DeviceStateWithDependencies<R> {
        override val dependencies: Collection<DeviceState<*>> = listOf(this@map)
        override val stateValue: ObservedValue<R?> get() = this@map.stateValue.map(mapper)
        override val stateFlow: Flow<ObservedValue<R?>> = this@map.stateFlow.map { it.map(mapper) }
    }

/**
 * Flat-maps this state into another [DeviceState], switching sources on every value change.
 * Cancels the previous subscription automatically when the source changes.
 *
 * Usage:
 * ```
 * val highRange by mutableState(100.0)
 * val lowRange by mutableState(0.0)
 * val target by mutableState(50.0)
 * val selected by target.flatMap { if (it > 50) highRange else lowRange }
 * ```
 */
@OptIn(ExperimentalCoroutinesApi::class)
public fun <T, R> DeviceState<T>.flatMap(
    mapper: (T?) -> DeviceState<R>,
): DeviceStateWithDependencies<R> =
    object : DeviceStateWithDependencies<R> {
        override val dependencies: Collection<DeviceState<*>> get() = listOf(this@flatMap)
        override val stateValue: ObservedValue<R?> get() = mapper(this@flatMap.value).stateValue
        override val stateFlow: Flow<ObservedValue<R?>> =
            this@flatMap.stateFlow.flatMapLatest { sv -> mapper(sv.value).stateFlow }
    }

/**
 * Combines two device states into a single read-only [DeviceState].
 * The combined value is computed by applying [mapper] to the latest values of both source states.
 */
public fun <T1, T2, R> DeviceState<T1>.combine(
    other: DeviceState<T2>,
    mapper: (T1?, T2?) -> R?,
): DeviceStateWithDependencies<R> = object : DeviceStateWithDependencies<R> {
    override val dependencies: Collection<DeviceState<*>> = listOf(this@combine, other)
    override val stateValue: ObservedValue<R?> get() = ObservedValue.combine(this@combine.stateValue, other.stateValue, mapper)
    override val stateFlow: Flow<ObservedValue<R?>> = combine(
        this@combine.stateFlow,
        other.stateFlow
    ) { s1, s2 ->
        ObservedValue.combine(s1, s2, mapper)
    }
}

/**
 * Converts a [Flow] of values into a [DeviceState].
 * Each emitted value becomes the new state value, timestamped now.
 *
 * @param scope The [CoroutineScope] in which state collection is active.
 * @param initialValue The initial value before the flow emits its first item.
 */
public fun <T> Flow<T>.asDeviceState(
    scope: CoroutineScope,
    initialValue: T,
    clock: Clock = Clock.System,
): DeviceState<T> {
    val stateFlow: StateFlow<ObservedValue<T?>> = this.map { observed(it, clock) }.stateIn(
        scope,
        SharingStarted.Lazily,
        observed(initialValue, clock)
    )
    return object : DeviceState<T> {
        override val stateValue: ObservedValue<T?> get() = stateFlow.value
        override val stateFlow: StateFlow<ObservedValue<T?>> get() = stateFlow
    }
}
