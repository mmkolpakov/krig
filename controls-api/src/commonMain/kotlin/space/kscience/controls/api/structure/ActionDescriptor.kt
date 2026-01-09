package space.kscience.controls.api.structure

import kotlinx.serialization.Serializable
import space.kscience.controls.common.meta.serializableToMeta
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.descriptors.MetaDescriptor
import space.kscience.dataforge.names.Name

/**
 * A serializable, self-contained descriptor for a device action. This object provides all the static information
 * about an action, which can be used for UI generation, validation, and remote invocation.
 *
 * @property name The unique, potentially hierarchical name of the action. Uses [Name] for consistency.
 * @property inputMetaDescriptor A descriptor for the action's input [Meta].
 * @property outputDescriptor A descriptor for the action's output [Meta].
 * @property attributes Additional configuration (execution policy, timeouts, UI) stored as Meta.
 */
@Serializable
public data class ActionDescriptor(
    public override val name: Name,
    public val inputMetaDescriptor: MetaDescriptor = MetaDescriptor(),
    public val outputDescriptor: MetaDescriptor = MetaDescriptor(),
    override val attributes: Meta = Meta.EMPTY
) : MemberDescriptor {
    override fun toMeta(): Meta = serializableToMeta(serializer(), this)

    public companion object {
        public const val TYPE: String = "action"
    }
}