package space.kscience.controls.api.descriptors

import space.kscience.dataforge.meta.MetaRepr
import space.kscience.dataforge.names.Name

/**
 * A foundational, sealed interface for all declarative descriptors of device members
 * (properties, actions, and streams).
 */
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
