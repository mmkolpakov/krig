package space.kscience.krig.api.descriptors

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import space.kscience.attributes.Attributes
import space.kscience.dataforge.meta.descriptors.MetaDescriptor
import space.kscience.dataforge.names.Name

/** Static contract of a device action. Input/output descriptors describe Meta payload shape. */
@Serializable
@SerialName("descriptor.action")
public data class ActionDescriptor(
    public override val name: Name,
    public val inputMetaDescriptor: MetaDescriptor = MetaDescriptor(),
    public val outputMetaDescriptor: MetaDescriptor = MetaDescriptor(),
    @Serializable(with = OperationAttributesSerializer::class)
    override val attributes: OperationAttributes = Attributes.EMPTY,
) : OperationDescriptor {
    public companion object {
        public const val TYPE: String = "action"
    }
}
