package space.kscience.krig.api.descriptors

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import space.kscience.attributes.Attributes
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
    @Serializable(with = OperationAttributesSerializer::class)
    override val attributes: OperationAttributes = Attributes.EMPTY,
) : OperationDescriptor {
    public fun <T> findAttribute(key: space.kscience.attributes.Attribute<T>): T? = attributes[key]

    public companion object {
        public const val TYPE: String = "property"
    }
}
