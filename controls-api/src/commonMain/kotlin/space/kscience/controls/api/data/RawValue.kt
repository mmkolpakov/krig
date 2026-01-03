package space.kscience.controls.api.data

import kotlinx.serialization.Serializable
import space.kscience.dataforge.meta.Meta
import kotlin.jvm.JvmInline

/**
 * A lightweight, serializable wrapper for raw data values.
 *
 * Unlike DataForge's `Value` or `Meta`, this hierarchy is designed for the "Data Plane":
 * it minimizes overhead for high-frequency telemetry by using [JvmInline] classes where possible
 * and avoiding deep object graphs.
 *
 * It strictly corresponds to the [DataType] enumeration.
 */
@Serializable
public sealed interface RawValue {

    /** Represents a 32-bit signed integer value. */
    @Serializable
    @JvmInline
    public value class I(public val value: Int) : RawValue

    /** Represents a 64-bit signed integer value. */
    @Serializable
    @JvmInline
    public value class L(public val value: Long) : RawValue

    /** Represents a 32-bit floating point value. */
    @Serializable
    @JvmInline
    public value class F(public val value: Float) : RawValue

    /** Represents a 64-bit floating point value. */
    @Serializable
    @JvmInline
    public value class D(public val value: Double) : RawValue

    /** Represents a boolean value. */
    @Serializable
    @JvmInline
    public value class B(public val value: Boolean) : RawValue

    /** Represents a string value. */
    @Serializable
    @JvmInline
    public value class S(public val value: String) : RawValue

    /**
     * Represents raw binary data (Opaque Blob).
     * Corresponds to [DataType.BINARY].
     */
    @Serializable
    public data class Bin(public val value: ByteArray) : RawValue {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other == null || this::class != other::class) return false
            other as Bin
            return value.contentEquals(other.value)
        }

        override fun hashCode(): Int = value.contentHashCode()
    }

    // --- Optimization Arrays ---

    /**
     * Represents an optimized array of doubles.
     * Corresponds to [DataType.DOUBLE_ARRAY].
     */
    @Serializable
    public data class DArr(public val value: DoubleArray) : RawValue {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other == null || this::class != other::class) return false
            other as DArr
            return value.contentEquals(other.value)
        }

        override fun hashCode(): Int = value.contentHashCode()
    }

    /**
     * Represents an optimized array of signed integers.
     * Corresponds to [DataType.INT_ARRAY].
     */
    @Serializable
    public data class IArr(public val value: IntArray) : RawValue {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other == null || this::class != other::class) return false
            other as IArr
            return value.contentEquals(other.value)
        }

        override fun hashCode(): Int = value.contentHashCode()
    }

    /**
     * Represents an array of bytes (Numerical).
     * Corresponds to [DataType.BYTE_ARRAY].
     * distinct from [Bin] which implies an opaque blob.
     */
    @Serializable
    public data class BArr(public val value: ByteArray) : RawValue {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other == null || this::class != other::class) return false
            other as BArr
            return value.contentEquals(other.value)
        }

        override fun hashCode(): Int = value.contentHashCode()
    }

    // --- Unsigned Types (mapped to inline classes for transport) ---

    /** Represents an 8-bit unsigned integer. */
    @Serializable
    @JvmInline
    public value class UB(public val value: UByte) : RawValue

    /** Represents a 16-bit unsigned integer. */
    @Serializable
    @JvmInline
    public value class US(public val value: UShort) : RawValue

    /** Represents a 32-bit unsigned integer. */
    @Serializable
    @JvmInline
    public value class UI(public val value: UInt) : RawValue

    /** Represents a 64-bit unsigned integer. */
    @Serializable
    @JvmInline
    public value class UL(public val value: ULong) : RawValue

    // --- Complex Types ---

    /**
     * Represents a tuple (structure) of raw values.
     * Corresponds to [DataType.RECORD].
     * Useful for atomic transmission of multi-component data (e.g. vectors, colors) without overhead.
     */
    @Serializable
    public data class Record(public val fields: List<RawValue>) : RawValue

    /**
     * A fallback wrapper for complex [Meta] structures.
     * Use this only when the value cannot be represented by primitives (e.g. complex configuration).
     */
    @Serializable
    public data class M(public val value: Meta) : RawValue
}