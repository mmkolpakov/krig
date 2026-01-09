package space.kscience.controls.common.serialization

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.jvm.JvmInline

/**
 * A lightweight, inline wrapper around [ByteArray] providing strict Base64 serialization
 * and safe string representation for logging.
 *
 * Designed for High-Throughput systems:
 * 1. **Log-Safe**: [toString] returns metadata (hash/size) instead of dumping content.
 * 2. **Content-Based Equality**: Implements [contentEquals] and cached [contentHashCode].
 * 3. **Validation**: Strict error handling during deserialization.
 *
 * Note: This is not a value class to ensure correct usage in Maps/Sets (content-based equality).
 *
 * @property bytes The underlying byte array. Exposed for zero-copy access.
 */
@Serializable(with = Base64BytesSerializer::class)
public class Base64Bytes(public val bytes: ByteArray) {

    /**
     * The size of the data in bytes.
     */
    public val size: Int get() = bytes.size

    private val _hashCode: Int by lazy(LazyThreadSafetyMode.PUBLICATION) {
        bytes.contentHashCode()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false
        other as Base64Bytes
        if (size != other.size) return false
        return bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int = _hashCode

    /**
     * Returns a log-safe string representation containing metadata only.
     * Example: `Base64Bytes(size=1024, hash=a1b2c3d4)`
     */
    override fun toString(): String {
        return "Base64Bytes(size=$size, hash=${_hashCode.toString(16)})"
    }

    public companion object {
        public val EMPTY: Base64Bytes = Base64Bytes(ByteArray(0))

        /**
         * Creates a [Base64Bytes] from a UTF-8 string.
         */
        public fun fromString(source: String): Base64Bytes = Base64Bytes(source.encodeToByteArray())
    }
}

/**
 * A validating serializer for [Base64Bytes].
 * Uses the standard library [kotlin.io.encoding.Base64].
 */
public object Base64BytesSerializer : KSerializer<Base64Bytes> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("space.kscience.controls.common.serialization.Base64Bytes", PrimitiveKind.STRING)

    @OptIn(ExperimentalEncodingApi::class)
    override fun serialize(encoder: Encoder, value: Base64Bytes) {
        val text = Base64.encode(value.bytes)
        encoder.encodeString(text)
    }

    @OptIn(ExperimentalEncodingApi::class)
    override fun deserialize(decoder: Decoder): Base64Bytes {
        val text = decoder.decodeString()
        return try {
            Base64Bytes(Base64.decode(text))
        } catch (e: IllegalArgumentException) {
            throw SerializationException("Failed to decode Base64Bytes: invalid Base64 input", e)
        }
    }
}