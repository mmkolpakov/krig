package space.kscience.krig.api.faults

import space.kscience.dataforge.meta.MetaRepr

/**
 * Predictable business fault — the negative-but-expected outcome of an operation (invalid
 * input, wrong device state). Serializable; travels on the wire inside
 * [SerializableDeviceFailure] or `DeviceErrorMessage`. Local code uses exceptions.
 */
public interface DeviceFault : MetaRepr {
    /** Stable machine-readable code (e.g. `"VALIDATION_ERROR"`). Wire-frozen between minor versions. */
    public val code: String

    /** Human-readable description for consoles and logs. Defaults to [code]. */
    public val message: String get() = code
}
