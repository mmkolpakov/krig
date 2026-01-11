package space.kscience.controls.core.contracts

import space.kscience.controls.core.meta.DevicePropertySpec

/**
 * A typed, low-overhead tunnel for accessing device properties, bypassing the [space.kscience.dataforge.meta.Meta] boxing.
 *
 * Accessors are intended for the "Data Plane" (hot control loops, telemetry streaming), whereas
 * [space.kscience.controls.core.contracts.PropertyDevice.readProperty] is intended for the "Control Plane" (configuration, UI).
 *
 * @param T The type of the property value.
 */
public sealed interface PropertyAccessor<T> {
    /**
     * The specification of the property this accessor is bound to.
     */
    public val propertySpec: DevicePropertySpec<*, T>
}

/**
 * A specialized accessor for [Double] properties to ensure zero-allocation read/write operations.
 * Used for high-frequency telemetry and analog control.
 */
public interface DoubleAccessor : PropertyAccessor<Double> {
    /**
     * Reads the current value of the property directly from the driver.
     * This is a suspendable operation to support I/O-bound drivers.
     */
    public suspend fun read(): Double

    /**
     * Writes a new value to the property directly to the driver.
     */
    public suspend fun write(value: Double)

    /**
     * Attempts to read the value synchronously without blocking.
     * Useful for drivers that maintain a local atomic cache of the value.
     *
     * @return The value if available immediately, or [Double.NaN] if I/O is required or value is unavailable.
     */
    public fun tryRead(): Double = Double.NaN
}

/**
 * A specialized accessor for [Int] properties to ensure zero-allocation read/write operations.
 * Used for discrete states, counters, and digital control.
 */
public interface IntAccessor : PropertyAccessor<Int> {
    /**
     * Reads the current value of the property directly from the driver.
     */
    public suspend fun read(): Int

    /**
     * Writes a new value to the property directly to the driver.
     */
    public suspend fun write(value: Int)

    /**
     * Attempts to read the value synchronously without blocking.
     *
     * @return The value if available immediately, or `null` if I/O is required.
     */
    public fun tryRead(): Int? = null
}

/**
 * A generic fallback accessor for complex types or types that do not have a specialized primitive interface.
 * While this may involve boxing of [T], it still bypasses the overhead of [space.kscience.dataforge.meta.Meta] parsing
 * if the driver supports it.
 */
public interface GenericAccessor<T> : PropertyAccessor<T> {
    /**
     * Reads the value.
     */
    public suspend fun read(): T

    /**
     * Writes the value.
     */
    public suspend fun write(value: T)
}