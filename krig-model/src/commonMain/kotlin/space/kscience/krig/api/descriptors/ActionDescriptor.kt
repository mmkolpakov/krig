package space.kscience.krig.api.descriptors

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import space.kscience.attributes.Attributes
import space.kscience.dataforge.meta.descriptors.MetaDescriptor
import space.kscience.dataforge.names.Name

/**
 * A serializable, self-contained descriptor for a device action. This object provides all the static information
 * about an action, which can be used for UI generation, validation, and remote invocation.
 *
 * @property name The unique, potentially hierarchical name of the action. Uses [space.kscience.dataforge.names.Name] for consistency.
 * @property inputMetaDescriptor A descriptor for the action's input [space.kscience.dataforge.meta.Meta].
 * @property outputDescriptor A descriptor for the action's output [space.kscience.dataforge.meta.Meta].
 * @property attributes Composable attribute container for this action.
 */
@Serializable
@SerialName("descriptor.action")
public data class ActionDescriptor(
    public override val name: Name,
    public val inputMetaDescriptor: MetaDescriptor = MetaDescriptor(),
    public val outputDescriptor: MetaDescriptor = MetaDescriptor(),
    @Serializable(with = OperationAttributesSerializer::class)
    override val attributes: OperationAttributes = Attributes.EMPTY,
) : OperationDescriptor {
    public companion object {
        public const val TYPE: String = "action"
    }
}
