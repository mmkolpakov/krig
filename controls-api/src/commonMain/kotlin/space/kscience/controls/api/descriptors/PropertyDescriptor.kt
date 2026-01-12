package space.kscience.controls.api.descriptors

import kotlinx.serialization.Serializable
import space.kscience.controls.api.descriptors.attributes.AccessAttribute
import space.kscience.controls.api.descriptors.attributes.BehaviorAttribute
import space.kscience.controls.api.descriptors.attributes.MetadataAttribute
import space.kscience.controls.api.descriptors.attributes.PersistenceAttribute
import space.kscience.controls.api.descriptors.attributes.ValidationAttribute
import space.kscience.controls.api.spec.ResourceLockSpec
import space.kscience.controls.api.validation.ValidationRuleDescriptor
import space.kscience.controls.api.meta.serializableToMeta
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.Value
import space.kscience.dataforge.meta.descriptors.MetaDescriptor
import space.kscience.dataforge.meta.descriptors.allowedValues
import space.kscience.dataforge.names.Name
import kotlin.reflect.KType
import kotlin.time.Duration

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
 * @property attributes TODO desc
 */
@Serializable
public data class PropertyDescriptor(
    public override val name: Name,
    public val kind: PropertyKind,
    public val valueTypeName: String,
    public val metaDescriptor: MetaDescriptor = MetaDescriptor(),
    override val attributes: Set<MemberAttribute> = emptySet()
) : MemberDescriptor {
    override fun toMeta(): Meta = serializableToMeta(serializer(), this)

    public inline fun <reified A : MemberAttribute> findAttribute(): A? {
        return attributes.filterIsInstance<A>().firstOrNull()
    }

    public companion object {
        public const val TYPE: String = "property"
    }
}

public val PropertyDescriptor.readable: Boolean
    get() = attribute<AccessAttribute>()?.readable ?: true

public val PropertyDescriptor.mutable: Boolean
    get() = attribute<AccessAttribute>()?.mutable ?: false

public val PropertyDescriptor.timeout: Duration?
    get() = attribute<BehaviorAttribute>()?.timeout

public val PropertyDescriptor.requiredLocks: List<ResourceLockSpec>
    get() = attribute<BehaviorAttribute>()?.requiredLocks ?: emptyList()

public val PropertyDescriptor.persistent: Boolean
    get() = attribute<PersistenceAttribute>()?.persistent ?: false

public val PropertyDescriptor.validationRules: List<ValidationRuleDescriptor>
    get() = attribute<ValidationAttribute>()?.rules ?: emptyList()

/**
 * Aggregates allowed values from both the [ValidationAttribute] (if present)
 * and the structural [MetaDescriptor].
 */
public val PropertyDescriptor.allowedValues: List<Value>?
    get() = attribute<ValidationAttribute>()?.allowedValues ?: metaDescriptor.allowedValues

public val PropertyDescriptor.unit: String?
    get() = findAttribute<MetadataAttribute>()?.unit

public val PropertyDescriptor.minValue: Double?
    get() = findAttribute<ValidationAttribute>()?.rangeMin

public val PropertyDescriptor.maxValue: Double?
    get() = findAttribute<ValidationAttribute>()?.rangeMax