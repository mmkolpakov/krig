package space.kscience.krig.api.descriptors

import kotlinx.serialization.Polymorphic
import space.kscience.krig.api.annotations.PolymorphicBase
import space.kscience.dataforge.meta.MetaRepr
import space.kscience.dataforge.names.Name

/**
 * Declarative descriptor of an operation target. Open polymorphic:
 * third parties add new kinds via `@SerialName` subclasses and register them through
 * `SerializationContributor`.
 */
@Polymorphic
@PolymorphicBase
public interface OperationDescriptor : MetaRepr {
    /**
     * The unique, potentially hierarchical operation name.
     */
    public val name: Name

    /**
     * Attributes defining behavior, metadata, and policies.
     */
    public val attributes: Set<OperationAttribute>
}

/**
 * Universal accessor for attributes.
 * Usage: `descriptor.attribute<MetadataAttribute>()?.description`
 */
public inline fun <reified A : OperationAttribute> OperationDescriptor.attribute(): A? {
    return attributes.filterIsInstance<A>().firstOrNull()
}
