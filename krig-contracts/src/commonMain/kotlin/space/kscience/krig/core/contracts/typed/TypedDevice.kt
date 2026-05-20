package space.kscience.krig.core.contracts.typed

import space.kscience.krig.core.meta.DeviceActionContract
import space.kscience.krig.core.meta.DevicePropertyContract
import space.kscience.krig.core.meta.MutableDevicePropertyContract

/**
 * Typed contract on the data plane. Replaces `readProperty(Name): Meta` as the primary
 * surface — `Meta`-returning APIs become serialisation adapters built on top of
 * [reader] / [writer].
 *
 * **Lifecycle**: [reader] / [writer] return per-property handles that may be cached by
 * the driver. Each call must produce a handle whose contract matches the property descriptor;
 * primitive-specialised paths are exposed through [sampler] for streaming workloads.
 *
 * **Cross-cutting**: gates, locks, timeout, retry and observers live on operation
 * pipeline specs keyed by open `OperationKind` names.
 *
 * **Sampling**: [sampler] is opt-in. Drivers that natively publish into a slot
 * implement it for zero-allocation streaming;
 * everything else returns `null` and falls back to per-call [TypedReader.read].
 */
public interface TypedDevice {
    /** Returns a typed read handle for the given property spec. */
    public fun <T> reader(spec: DevicePropertyContract<T>): TypedReader<T>

    /** Returns a typed write handle for the given mutable property spec. */
    public fun <T> writer(spec: MutableDevicePropertyContract<T>): TypedWriter<T>

    /**
     * Returns a typed sampler if the driver supports lock-free streaming for this property;
     * `null` otherwise. Callers fall back to [reader] for one-shot reads.
     */
    public fun <T> sampler(spec: DevicePropertyContract<T>): TypedSampler<T>? = null

    /** Returns a typed action handle for the given action spec. */
    public fun <I, O> action(spec: DeviceActionContract<I, O>): TypedAction<I, O> =
        GenericTypedAction { error("Action '${spec.name}' is not supported by this device") }
}
