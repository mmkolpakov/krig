package space.kscience.controls.core.contracts

import space.kscience.controls.core.meta.DevicePropertySpec

/**
 * A typed, low-overhead tunnel for accessing device properties, bypassing the [space.kscience.dataforge.meta.Meta] boxing.
 *
 * Accessors constitute the **Data Plane** of the framework. They are intended for hot control loops,
 * high-frequency telemetry, and real-time visualization, where the overhead of creating `Meta` objects
 * and parsing them is unacceptable.
 *
 * @param T The type of the property value.
 */
public interface PropertyAccessor<T> {
    /**
     * The specification of the property this accessor is bound to.
     */
    public val propertySpec: DevicePropertySpec<*, T>
}

/**
 * A specialized accessor for [Double] properties to ensure zero-allocation read/write operations.
 * Used for high-frequency analog control and telemetry.
 */
public interface DoubleAccessor : PropertyAccessor<Double> {
    /**
     * Reads the current value of the property directly from the driver/hardware.
     */
    public suspend fun readDouble(): Double

    /**
     * Writes a new value to the property directly to the driver/hardware.
     */
    public suspend fun writeDouble(value: Double)
}

/**
 * A specialized accessor for [Int] properties to ensure zero-allocation read/write operations.
 * Used for discrete states, counters, and registers.
 */
public interface IntAccessor : PropertyAccessor<Int> {
    /**
     * Reads the current value of the property directly from the driver/hardware.
     */
    public suspend fun readInt(): Int

    /**
     * Writes a new value to the property directly to the driver/hardware.
     */
    public suspend fun writeInt(value: Int)
}

/**
 * A specialized accessor for reading arrays of data (e.g., waveforms, spectra, images)
 * into a pre-allocated buffer. This enables **Zero-Allocation** data transfer patterns,
 * which are critical for performance in garbage-collected environments (JVM, Native).
 *
 * @param T The type of the array (e.g., `DoubleArray`, `IntArray`, `ByteArray`).
 */
public interface ArrayAccessor<T> : PropertyAccessor<T> {
    /**
     * Reads the property value directly into the provided [buffer].
     *
     * @param buffer The destination array/buffer.
     * @param offset The index in the [buffer] at which to start writing data.
     * @param size The maximum number of elements to read.
     * @return The actual number of elements read and written to the buffer.
     */
    public suspend fun readInto(buffer: T, offset: Int, size: Int): Int
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