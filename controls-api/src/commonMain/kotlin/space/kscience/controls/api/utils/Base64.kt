package space.kscience.controls.api.utils

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.io.encoding.Base64
import kotlin.jvm.JvmInline

@JvmInline
@Serializable(with = Base64BytesSerializer::class)
public value class Base64Bytes(public val bytes: ByteArray)

/**
 * A serializer for `ByteArray` that encodes to and decodes from a Base64 string using `Base64Bytes`.
 */
public object Base64BytesSerializer : KSerializer<Base64Bytes> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("Base64Bytes", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Base64Bytes) {
        encoder.encodeString(Base64.encode(value.bytes))
    }

    override fun deserialize(decoder: Decoder): Base64Bytes {
        return Base64Bytes(Base64.decode(decoder.decodeString()))
    }
}