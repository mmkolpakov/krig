package space.kscience.krig.api.descriptors

import kotlinx.serialization.Polymorphic
import space.kscience.krig.api.annotations.PolymorphicBase
import space.kscience.attributes.Attribute
import space.kscience.dataforge.names.Name

/**
 * Declarative descriptor of an operation target. Open polymorphic:
 * third parties add new kinds via `@SerialName` subclasses and register them through
 * `SerializationContributor`.
 */
@Polymorphic
@PolymorphicBase
public interface OperationDescriptor {
    /**
     * The unique, potentially hierarchical operation name.
     */
    public val name: Name

    /**
     * Attributes defining behavior, metadata, and policies.
     */
    public val attributes: OperationAttributes
}

/**
 * Universal typed-key accessor for operation attributes.
 */
public fun <T> OperationDescriptor.attribute(key: Attribute<T>): T? = attributes[key]
