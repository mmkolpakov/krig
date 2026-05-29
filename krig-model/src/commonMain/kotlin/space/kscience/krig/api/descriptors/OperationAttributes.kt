package space.kscience.krig.api.descriptors

import kotlinx.serialization.KSerializer
import space.kscience.attributes.Attribute
import space.kscience.attributes.Attributes
import space.kscience.attributes.AttributesBuilder
import space.kscience.attributes.serialization.AttributesSerializer
import space.kscience.attributes.serialization.SerializableAttribute
import space.kscience.attributes.withAttribute
import space.kscience.krig.api.descriptors.attributes.OperationAttributeKeys

/** Attribute container used by [OperationDescriptor]. */
public typealias OperationAttributes = Attributes

/**
 * Serializable descriptor-attribute key.
 *
 * Values are regular serializable DTOs; lookup and composition use `attributes-kt`
 * key semantics instead of scanning a bag of marker objects.
 */
public abstract class OperationAttributeKey<T>(
    serialId: String,
    serializer: KSerializer<T>,
) : SerializableAttribute<T>(serialId, serializer)

/** Serializer for the standard KRig descriptor attributes. */
public object OperationAttributesSerializer : KSerializer<Attributes> by AttributesSerializer(OperationAttributeKeys.standard)

/** Typed key-value entry for small descriptor attribute builders. */
public data class OperationAttributeEntry<T>(
    public val key: Attribute<T>,
    public val value: T,
)

public infix fun <T> Attribute<T>.of(value: T): OperationAttributeEntry<T> =
    OperationAttributeEntry(this, value)

private fun <T> Attributes.withEntry(entry: OperationAttributeEntry<T>): Attributes =
    withAttribute(entry.key, entry.value)

/** Builds operation attributes from explicit typed entries. */
public fun operationAttributesOf(vararg entries: OperationAttributeEntry<*>): Attributes {
    var result = Attributes.EMPTY
    entries.forEach { entry ->
        result = result.withEntry(entry)
    }
    return result
}

/** Builds operation attributes using the native `attributes-kt` builder. */
public fun operationAttributes(
    builder: AttributesBuilder<OperationDescriptor>.() -> Unit,
): Attributes = Attributes(builder)
