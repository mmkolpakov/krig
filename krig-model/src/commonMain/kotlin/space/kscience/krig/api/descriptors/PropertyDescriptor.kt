package space.kscience.krig.api.descriptors

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import space.kscience.attributes.Attributes
import space.kscience.dataforge.meta.descriptors.MetaDescriptor
import space.kscience.dataforge.names.Name

/** Static contract of a device property. [metaDescriptor] describes the property's Meta payload shape. */
@Serializable
@SerialName("descriptor.property")
public data class PropertyDescriptor(
    public override val name: Name,
    public val kind: PropertyKind,
    public val valueTypeId: TypeId,
    public val metaDescriptor: MetaDescriptor = MetaDescriptor(),
    @Serializable(with = OperationAttributesSerializer::class)
    override val attributes: OperationAttributes = Attributes.EMPTY,
) : OperationDescriptor {
    public companion object {
        public const val TYPE: String = "property"
    }
}
