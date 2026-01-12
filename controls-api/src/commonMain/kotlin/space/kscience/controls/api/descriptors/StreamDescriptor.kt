package space.kscience.controls.api.descriptors

import kotlinx.serialization.Serializable
import space.kscience.controls.api.meta.serializableToMeta
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.misc.DfType
import space.kscience.dataforge.names.Name

/**
 * A serializable, self-contained descriptor for a device's data stream.
 * This object provides all the static information about a stream, making it suitable for
 * introspection and client discovery without needing a live device instance.
 *
 * @property name The unique, potentially hierarchical name of the stream.
 * @property dataTypeFqName The fully-qualified class name of the primary data objects or frames
 *                          being transmitted over the stream. This serves as a hint for clients
 *                          on how to decode the raw byte stream.
 * @property attributes TODO desc
 */
@Serializable
@DfType("device.stream")
public data class StreamDescriptor(
    override val name: Name,
    val dataTypeFqName: String,
    override val attributes: Set<MemberAttribute> = emptySet()
) : MemberDescriptor {
    override fun toMeta(): Meta = serializableToMeta(serializer(), this)

    public companion object {
        public const val TYPE: String = "device.stream"
    }
}
