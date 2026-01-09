package space.kscience.controls.api.data

import kotlinx.serialization.Serializable
import space.kscience.dataforge.meta.Meta
import kotlin.jvm.JvmInline

/**
 * A lightweight, serializable wrapper for raw data values.
 * This hierarchy is designed for the "Write Path" (Commands) and "Slow Path" (Events).
 * It minimizes overhead using [JvmInline] classes where possible.
 *
 * For the "Fast Path" (Internal Driver Loop), primitives (Double, Long) should be used directly
 * without wrapping into [RawValue].
 *
 * See `RawValueExtensions.kt` for safe coercion methods.
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

    /** Represents a UTF-8 string value. */
    @Serializable
    @JvmInline
    public value class S(public val value: String) : RawValue

    /**
     * Represents raw binary data (Opaque Blob).
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

    @Serializable
    public data class DArr(public val value: DoubleArray) : RawValue {
        override fun equals(other: Any?): Boolean = other is DArr && value.contentEquals(other.value)
        override fun hashCode(): Int = value.contentHashCode()
    }

    @Serializable
    public data class IArr(public val value: IntArray) : RawValue {
        override fun equals(other: Any?): Boolean = other is IArr && value.contentEquals(other.value)
        override fun hashCode(): Int = value.contentHashCode()
    }

    // --- Unsigned Types ---

    @Serializable @JvmInline public value class UB(public val value: UByte) : RawValue
    @Serializable @JvmInline public value class US(public val value: UShort) : RawValue
    @Serializable @JvmInline public value class UI(public val value: UInt) : RawValue
    @Serializable @JvmInline public value class UL(public val value: ULong) : RawValue

    // --- Complex Types ---

    /**
     * A record/struct wrapper. Used for efficient transmission of multi-value data points (e.g. coordinates).
     */
    @Serializable
    public data class Record(public val fields: List<RawValue>) : RawValue

    /**
     * A wrapper for DataForge Meta. Used for complex configuration objects.
     */
    @Serializable
    public data class M(public val value: Meta) : RawValue

    /**
     * Represents a raw JSON string to be parsed lazily.
     */
    @Serializable
    @JvmInline
    public value class JsonRaw(public val jsonString: String) : RawValue
}