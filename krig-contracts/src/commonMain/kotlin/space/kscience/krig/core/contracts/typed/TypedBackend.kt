package space.kscience.krig.core.contracts.typed

import space.kscience.dataforge.names.Name
import space.kscience.krig.api.data.ObservedValue
import space.kscience.krig.api.faults.GenericOperationFault
import space.kscience.krig.api.faults.OperationFaultTypes
import space.kscience.krig.api.result.OperationOutcome
import space.kscience.krig.api.result.runCatchingOperation
import space.kscience.krig.core.UnstableKrigForSubclassing
import space.kscience.krig.core.contracts.DeviceBackend
import space.kscience.krig.core.meta.DeviceActionContract
import space.kscience.krig.core.meta.DevicePropertyContract
import space.kscience.krig.core.meta.MutableDevicePropertyContract

/**
 * Optional typed data-plane SPI for [DeviceBackend] implementations.
 *
 * Backends implement this when they can expose native typed handles without crossing
 * the `Meta` serialization boundary. Returning `null` keeps the native handle absent;
 * device wrappers can still use the documented `Meta` fallback path.
 */
public interface TypedBackend {
    /** Native typed read handle, or `null` to use the `Meta` fallback. */
    public fun <T> reader(spec: DevicePropertyContract<T>): TypedReader<T>?

    /** Native quality-aware typed read handle, or `null` to use the `Meta` observed fallback. */
    public fun <T> observedReader(spec: DevicePropertyContract<T>): TypedObservedReader<T>? = null

    /** Native typed write handle, or `null` to use the `Meta` fallback. */
    public fun <T> writer(spec: MutableDevicePropertyContract<T>): TypedWriter<T>?

    /** Native sampler, or `null` when streaming is not supported. */
    public fun <T> sampler(spec: DevicePropertyContract<T>): TypedSampler<T>? = null

    /** Native typed action handle, or `null` to use the `Meta` fallback. */
    public fun <I, O> action(spec: DeviceActionContract<I, O>): TypedAction<I, O>? = null
}

/** Backend type returned by [deviceBackend][space.kscience.krig.core.contracts.deviceBackend]. */
@OptIn(UnstableKrigForSubclassing::class)
public interface TypedDeviceBackend : DeviceBackend, TypedBackend {
    /** Registered typed property spec by name, used to keep the Meta boundary converter-aware. */
    public fun propertySpec(name: Name): DevicePropertyContract<*>? = null

    /** Registered typed action spec by name, used to keep the Meta boundary converter-aware. */
    public fun actionSpec(name: Name): DeviceActionContract<*, *>? = null

    /** All registered property specs keyed by name — the enumeration counterpart of [propertySpec]. */
    public fun propertySpecs(): Map<Name, DevicePropertyContract<*>> = emptyMap()

    /** All registered action specs keyed by name — the enumeration counterpart of [actionSpec]. */
    public fun actionSpecs(): Map<Name, DeviceActionContract<*, *>> = emptyMap()
}

/**
 * Reads [spec] through a native typed observed handle.
 *
 * This backend-only helper returns a predictable failure when the native handle is absent.
 * Use a `Device` wrapper when the `Meta` fallback path should be available.
 */
public suspend fun <T> TypedBackend.readObservedOutcome(
    spec: DevicePropertyContract<T>,
): OperationOutcome<ObservedValue<T?>> {
    val handle = observedReader(spec)
        ?: return OperationOutcome.Fail(
            GenericOperationFault(
                faultType = OperationFaultTypes.UnsupportedValue,
                message = "Typed observed reader for property '${spec.name}' is not available.",
            ),
        )
    return runCatchingOperation { handle.readObserved() }
}
