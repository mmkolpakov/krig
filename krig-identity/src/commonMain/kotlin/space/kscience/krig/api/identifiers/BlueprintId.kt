package space.kscience.krig.api.identifiers

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.jvm.JvmInline

/**
 * A type-safe identifier for a device blueprint.
 *
 * The ID should be unique across the system, typically following a reverse-DNS format
 * (e.g., "com.example.mydevice").
 *
 * @property value The underlying string representation of the blueprint ID.
 */
@Serializable(with = BlueprintId.Serializer::class)
@JvmInline
public value class BlueprintId(public val value: String) {
    override fun toString(): String = value

    /**
     * A custom serializer for [BlueprintId] that represents it as a plain string.
     */
    public object Serializer : KSerializer<BlueprintId> {
        override val descriptor: SerialDescriptor =
            PrimitiveSerialDescriptor("space.kscience.krig.api.identifiers.BlueprintId", PrimitiveKind.STRING)

        override fun serialize(encoder: Encoder, value: BlueprintId) {
            encoder.encodeString(value.value)
        }

        override fun deserialize(decoder: Decoder): BlueprintId {
            return BlueprintId(decoder.decodeString())
        }
    }
}

/**
 * Extension function to convert a [String] into a type-safe [BlueprintId].
 */
public fun String.toBlueprintId(): BlueprintId = BlueprintId(this)