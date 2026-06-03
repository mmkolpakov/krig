package space.kscience.krig.core.contracts.typed

import kotlinx.coroutines.flow.StateFlow
import space.kscience.krig.api.result.OperationOutcome
import space.kscience.krig.api.result.runCatchingOperation
import space.kscience.krig.core.meta.DeviceActionContract
import space.kscience.krig.core.meta.DevicePropertyContract
import space.kscience.krig.core.meta.MutableDevicePropertyContract

/** Typed access surface for property reads, writes, actions and optional samplers. */
public interface TypedDevice {
    /** Returns a typed read handle for the given property spec. */
    public fun <T> reader(spec: DevicePropertyContract<T>): TypedReader<T>

    /** Reads [spec] and returns predictable operation failures as values. */
    public suspend fun <T> readOutcome(spec: DevicePropertyContract<T>): OperationOutcome<T> =
        runCatchingOperation { reader(spec).read() }

    /** Returns a typed write handle for the given mutable property spec. */
    public fun <T> writer(spec: MutableDevicePropertyContract<T>): TypedWriter<T>

    /** Writes [value] and returns predictable operation failures as values. */
    public suspend fun <T> writeOutcome(
        spec: MutableDevicePropertyContract<T>,
        value: T,
    ): OperationOutcome<Unit> =
        runCatchingOperation { writer(spec).write(value) }

    /** Returns a typed sampler for this property, or `null` if the driver has none. */
    public fun <T> sampler(spec: DevicePropertyContract<T>): TypedSampler<T>? = null

    /**
     * Returns a live config-plane state (current value + updates) for this property, or `null`
     * if the driver keeps no native observable value (the `Meta` projection is used instead).
     * Mirrors [sampler] for the data plane; the role coroutines [StateFlow] / controls-kt
     * `DeviceState` play. Authorization is applied by the `typedPropertyState` accessor.
     */
    public fun <T> propertyState(spec: DevicePropertyContract<T>): StateFlow<T>? = null

    /** Returns a typed action handle for the given action spec. */
    public fun <I, O> action(spec: DeviceActionContract<I, O>): TypedAction<I, O> =
        TypedAction { error("Action '${spec.name}' is not supported by this device") }

    /** Executes [spec] and returns predictable operation failures as values. */
    public suspend fun <I, O> executeOutcome(
        spec: DeviceActionContract<I, O>,
        input: I,
    ): OperationOutcome<O?> =
        runCatchingOperation { action(spec).execute(input) }
}
