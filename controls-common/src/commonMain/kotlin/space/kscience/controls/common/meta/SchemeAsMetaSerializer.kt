package space.kscience.controls.common.meta

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import space.kscience.dataforge.meta.MetaSerializer
import space.kscience.dataforge.meta.Scheme
import space.kscience.dataforge.meta.SchemeSpec

/**
 * A generic KSerializer for any class that extends [space.kscience.dataforge.meta.Scheme].
 * It works by converting the Scheme to its [space.kscience.dataforge.meta.Meta] representation for serialization,
 * and then reading the [space.kscience.dataforge.meta.Meta] back to construct the Scheme instance using its [space.kscience.dataforge.meta.SchemeSpec].
 *
 * @param S The type of the [space.kscience.dataforge.meta.Scheme].
 * @param spec The [space.kscience.dataforge.meta.SchemeSpec] companion object for the scheme, which acts as a factory.
 */
public open class SchemeAsMetaSerializer<S : Scheme>(private val spec: SchemeSpec<S>) : KSerializer<S> {
    override val descriptor: SerialDescriptor get() = MetaSerializer.descriptor

    /**
     * Serializes the [Scheme] instance by first converting it to [space.kscience.dataforge.meta.Meta] and then
     * delegating the serialization to [MetaSerializer].
     */
    override fun serialize(encoder: Encoder, value: S) {
        encoder.encodeSerializableValue(MetaSerializer, value.toMeta())
    }

    /**
     * Deserializes a [Scheme] instance by first using [MetaSerializer] to get a [space.kscience.dataforge.meta.Meta] object,
     * and then using the provided [SchemeSpec] to read the meta into a typed Scheme object.
     */
    override fun deserialize(decoder: Decoder): S {
        val meta = decoder.decodeSerializableValue(MetaSerializer)
        return spec.read(meta)
    }
}