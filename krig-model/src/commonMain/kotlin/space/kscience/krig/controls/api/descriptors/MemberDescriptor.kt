package space.kscience.krig.api.descriptors

import kotlinx.serialization.Polymorphic
import space.kscience.krig.api.annotations.PolymorphicBase
import space.kscience.dataforge.meta.MetaRepr
import space.kscience.dataforge.names.Name

/**
 * Declarative descriptor of a device member (property, action, stream). Open polymorphic —
 * third parties add new kinds via `@SerialName`'d subclasses and register them through
 * `SerializationContributor`.
 */
@Polymorphic
@PolymorphicBase
public interface MemberDescriptor : MetaRepr {
    /**
     * The unique, potentially hierarchical name of the device member.
     */
    public val name: Name

    /**
     * The collection of attributes defining the member's behavior, metadata, and policies.
     */
    public val attributes: Set<MemberAttribute>
}

/**
 * Universal accessor for attributes.
 * Usage: `descriptor.attribute<MetadataAttribute>()?.description`
 */
public inline fun <reified A : MemberAttribute> MemberDescriptor.attribute(): A? {
    return attributes.filterIsInstance<A>().firstOrNull()
}
