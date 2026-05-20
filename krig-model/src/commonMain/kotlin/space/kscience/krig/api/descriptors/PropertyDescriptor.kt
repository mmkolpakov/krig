package space.kscience.krig.api.descriptors

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import space.kscience.krig.api.meta.serializableToMeta
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.descriptors.MetaDescriptor
import space.kscience.dataforge.names.Name

/**
 * Serializable descriptor of a device property. Introspectable without a live device.
 * [valueTypeId] is a stable SDK type identifier, not a runtime reflection name.
 */
@Serializable
@SerialName("descriptor.property")
public data class PropertyDescriptor(
    public override val name: Name,
    public val kind: PropertyKind,
    public val valueTypeId: String,
    public val metaDescriptor: MetaDescriptor = MetaDescriptor(),
    override val attributes: Set<OperationAttribute> = emptySet()
) : OperationDescriptor {
    override fun toMeta(): Meta = serializableToMeta(serializer(), this)

    public inline fun <reified A : OperationAttribute> findAttribute(): A? {
        return attributes.filterIsInstance<A>().firstOrNull()
    }

    public companion object {
        public const val TYPE: String = "property"
    }
}
