package space.kscience.controls.api.descriptors

import kotlinx.serialization.Transient
import space.kscience.controls.api.descriptors.attributes.AccessAttribute
import space.kscience.controls.api.descriptors.attributes.BindingsAttribute
import space.kscience.controls.api.descriptors.attributes.MetadataAttribute
import space.kscience.controls.api.identifiers.Permission
import space.kscience.controls.api.meta.AdapterBinding
import space.kscience.controls.api.meta.MemberTag
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

// --- Backward Compatibility Extensions ---

@property:Transient
public val MemberDescriptor.readPermissions: Set<Permission>
    get() = attribute<AccessAttribute>()?.readPermissions ?: emptySet()

@property:Transient
public val MemberDescriptor.writePermissions: Set<Permission>
    get() = attribute<AccessAttribute>()?.writePermissions ?: emptySet()

@property:Transient
public val MemberDescriptor.tags: Set<MemberTag>
    get() = attribute<MetadataAttribute>()?.tags ?: emptySet()

@property:Transient
public val MemberDescriptor.bindings: Map<String, AdapterBinding>
    get() = attribute<BindingsAttribute>()?.bindings ?: emptyMap()

@property:Transient
public val MemberDescriptor.description: String?
    get() = attribute<MetadataAttribute>()?.description