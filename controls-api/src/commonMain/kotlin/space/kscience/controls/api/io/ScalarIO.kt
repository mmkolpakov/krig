package space.kscience.controls.api.io

import space.kscience.dataforge.meta.Meta

/**
 * Interface for reading scalar values from a device.
 *
 * This interface constitutes the "Fast Path" for reading telemetry.
 * Implementations are expected to be highly optimized and thread-safe.
 *
 * **Performance & Semantics:**
 * - **Non-Blocking (Logical):** Ideally, these methods should return the latest value from an internal cache
 *   populated by a polling loop. If a blocking hardware call is required, it must be handled carefully to not
 *   stall the `DeviceEntity` supervisor scope.
 * - **Zero-Allocation:** Methods returning primitives (`readDouble`, `readInt`, `readLong`, `readBoolean`)
 *   must strictly avoid object allocation (boxing).
 * - **NaN/Error handling:**
 *   - For `readDouble`, standard IEEE 754 `NaN` can indicate missing data.
 *   - For other primitives, specific error handling strategies should be defined by the driver contract or via exceptions if the read fails strictly.
 */
public interface ScalarInputIO : DeviceIO {

    /**
     * Reads a 64-bit floating point value.
     *
     * Used for: Analog sensors, calculated metrics, precision measurements.
     *
     * @param token The property token (must be of type `TYPE_DOUBLE`).
     * @return The physical value.
     * @throws Exception If a hardware I/O error occurs.
     */
    public suspend fun readDouble(token: Int): Double

    /**
     * Reads a 32-bit signed integer.
     *
     * Used for: Registers, smaller counters, status codes.
     *
     * @param token The property token.
     * @return The integer value.
     * @throws Exception If a hardware I/O error occurs.
     */
    public suspend fun readInt(token: Int): Int

    /**
     * Reads a 64-bit signed integer.
     *
     * Used for: Timestamp registers, large counters, bitmasks > 32 bit.
     *
     * @param token The property token (must be of type `TYPE_LONG`).
     * @return The long value.
     * @throws Exception If a hardware I/O error occurs.
     */
    public suspend fun readLong(token: Int): Long

    /**
     * Reads a boolean flag.
     *
     * Used for: Digital Inputs (DI), coil status, discrete alarms.
     *
     * @param token The property token.
     * @return `true` for ON/High/1, `false` for OFF/Low/0.
     * @throws Exception If a hardware I/O error occurs.
     */
    public suspend fun readBoolean(token: Int): Boolean

    /**
     * Reads a structured value (Slow Path).
     *
     * @return The read Meta, or null if the value is not available.
     */
    public suspend fun readMeta(token: Int): Meta?
}

/**
 * Interface for writing scalar values to a device.
 *
 * Represents the "Write Path" (Command execution).
 * Implementations must ensure that the write operation is successfully committed to the transport layer.
 */
public interface ScalarOutputIO : DeviceIO {

    /**
     * Writes a 64-bit floating point value.
     *
     * @param token The property token.
     * @param value The value to write.
     */
    public suspend fun writeDouble(token: Int, value: Double)

    /**
     * Writes a 32-bit signed integer.
     *
     * @param token The property token.
     * @param value The value to write.
     */
    public suspend fun writeInt(token: Int, value: Int)

    /**
     * Writes a 64-bit signed integer.
     *
     * @param token The property token.
     * @param value The value to write.
     */
    public suspend fun writeLong(token: Int, value: Long)

    /**
     * Writes a boolean flag.
     *
     * @param token The property token.
     * @param value The value to write.
     */
    public suspend fun writeBoolean(token: Int, value: Boolean)

    /**
     * Writes a structured value (Slow Path).
     */
    public suspend fun writeMeta(token: Int, value: Meta)
}