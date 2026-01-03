package space.kscience.controls.api.data

import kotlinx.serialization.Serializable

/**
 * A strict enumeration of data types supported by the control system.
 * This replaces string-based type names to ensure runtime safety and
 * consistent serialization across platforms (JVM, JS, Native).
 *
 * It includes primitives for high-performance telemetry and complex types
 * for configuration.
 */
@Serializable
public enum class DataType {
    /** 32-bit signed integer. */
    INT,

    /** 64-bit signed integer. */
    LONG,

    /** 32-bit floating point number. */
    FLOAT,

    /** 64-bit floating point number. */
    DOUBLE,

    /** Boolean value (True/False). */
    BOOLEAN,

    /** UTF-8 String. */
    STRING,

    /** Raw binary data (ByteArray). */
    BINARY,

    /** Array of integers, optimized for telemetry (e.g. waveform). */
    INT_ARRAY,

    /** Array of doubles, optimized for telemetry (e.g. spectrum). */
    DOUBLE_ARRAY,

    /** Array of bytes. */
    BYTE_ARRAY,

    /**
     * A strictly ordered tuple of values (Record/Struct).
     * Used to transmit complex atomic data (e.g., {x, y, z} coordinates) without the overhead of Map/Meta.
     * The structure definition should be provided in the property descriptor.
     */
    RECORD,

    /**
     * A complex DataForge [space.kscience.dataforge.meta.Meta] structure.
     * Used for configuration and complex object transmission where flexibility outweighs performance.
     */
    META,

    /** An enumerated value. */
    ENUM
}