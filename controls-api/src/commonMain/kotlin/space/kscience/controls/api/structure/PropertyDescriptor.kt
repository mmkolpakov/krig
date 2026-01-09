package space.kscience.controls.api.structure

import kotlinx.serialization.Serializable
import space.kscience.controls.common.meta.serializableToMeta
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.descriptors.MetaDescriptor
import space.kscience.dataforge.names.Name
import kotlin.reflect.KType

/**
 * A serializable, self-contained descriptor for a device property. This object provides all the static information
 * about a property, making it suitable for introspection, UI generation, and validation without needing a live
 * device instance.
 *
 * @property name The unique, potentially hierarchical name of the property. Uses [Name] for consistency with DataForge.
 * @property kind The semantic [PropertyKind], classifying the property's nature (e.g., physical, logical).
 * @property valueTypeName The string representation of the property's [KType]. Essential for runtime type validation
 *                         in dynamic environments without reflection.
 * @property metaDescriptor A descriptor for the [Meta] value of the property, defining its structure and constraints.
 * @property attributes Additional configuration (validation, limits, persistence, UI) stored as Meta.
 */
@Serializable
public data class PropertyDescriptor(
    public override val name: Name,
    public val kind: PropertyKind,
    public val valueTypeName: String,
    public val metaDescriptor: MetaDescriptor = MetaDescriptor(),
    override val attributes: Meta = Meta.EMPTY
) : MemberDescriptor {
    override fun toMeta(): Meta = serializableToMeta(serializer(), this)

    public companion object {
        public const val TYPE: String = "property"
    }
}
