package space.kscience.krig.api.data

import kotlinx.serialization.Serializable
import space.kscience.dataforge.meta.Meta
import kotlin.jvm.JvmInline

/**
 * Serialized protocol/data-plane envelope for integrations that receive values whose
 * Kotlin type is only known at the protocol boundary. Flat `@JvmInline` variants avoid
 * `Value` / `Meta` overhead on that dynamic path.
 *
 * This is not a replacement for the strongly typed `TypedReader` / `TypedWriter` path:
 * SDK code that knows `T` should keep using contract specs.
 */
@Serializable
public sealed interface RawValue {
    public companion object

    @Serializable
    @JvmInline
    public value class Lng(public val value: Long) : RawValue

    @Serializable
    @JvmInline
    public value class Dbl(public val value: Double) : RawValue

    @Serializable
    @JvmInline
    public value class Bool(public val value: Boolean) : RawValue

    @Serializable
    @JvmInline
    public value class Str(public val value: String) : RawValue

    @Serializable
    public data class DblArr(public val value: DoubleArray) : RawValue {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other == null || this::class != other::class) return false
            other as DblArr
            return value.contentEquals(other.value)
        }

        override fun hashCode(): Int = value.contentHashCode()
    }

    /** Fallback for complex [Meta] values that do not fit the primitive variants. */
    @Serializable
    public data class MetaVal(public val value: Meta) : RawValue

    /**
     * Binary payload with a protocol-specific [typeId]. The id is taken from a
     * [ProtocolValueKey]; use `RawValue.of(key, value)` / `ext.decode(key)` rather
     * than writing [typeId] by hand.
     */
    @Serializable
    public data class Ext(
        public val value: ByteArray,
        public val typeId: String,
    ) : RawValue {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other == null || this::class != other::class) return false
            other as Ext
            return typeId == other.typeId && value.contentEquals(other.value)
        }

        override fun hashCode(): Int = 31 * value.contentHashCode() + typeId.hashCode()
    }
}
