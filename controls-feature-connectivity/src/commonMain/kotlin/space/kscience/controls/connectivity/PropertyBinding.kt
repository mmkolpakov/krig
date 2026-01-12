package space.kscience.controls.connectivity

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import space.kscience.controls.api.meta.serializableToMeta
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.MetaRepr
import space.kscience.dataforge.names.Name

/**
 * A sealed interface representing a declarative binding of a child's property.
 * This describes how a child property should be updated based on the parent's state or a constant value.
 * This interface is designed to be serializable for blueprint persistence.
 *
 * Type compatibility between source and target properties is checked by the runtime during device attachment.
 */
@Serializable
public sealed interface PropertyBinding : MetaRepr

/**
 * Binds a child's property to a constant [Meta] value.
 * This is useful for setting static configuration values on a child device.
 *
 * @property targetName The name of the property on the child device to be set.
 * @property value The constant [Meta] value to set.
 */
@Serializable
@SerialName("const")
public data class ConstPropertyBinding(
    val targetName: Name,
    val value: Meta,
) : PropertyBinding {
    override fun toMeta(): Meta = serializableToMeta(serializer(), this)
}

/**
 * Binds a child's property to a property of its parent device.
 * The runtime will create a subscription that automatically updates the child's property
 * whenever the parent's property changes.
 *
 * @property targetName The name of the property on the child device to be updated.
 * @property sourceName The name of the property on the parent device to read from.
 */
@Serializable
@SerialName("parent")
public data class ParentPropertyBinding(
    val targetName: Name,
    val sourceName: Name,
) : PropertyBinding {
    override fun toMeta(): Meta = serializableToMeta(serializer(), this)
}

/**
 * A binding that includes a serializable transformation descriptor.
 * This binding is created by the DSL when `using` is called.
 * The runtime uses the provided descriptor to find a [PropertyTransformerFactory] and create the
 * logic to convert the source value before applying it to the target.
 *
 * @property targetName The name of the property on the child device.
 * @property sourceName The name of the property on the parent device.
 * @property transformer The serializable descriptor that defines the value transformation.
 */
@Serializable
@SerialName("transformed")
public data class TransformedPropertyBinding(
    val targetName: Name,
    val sourceName: Name,
    val transformer: PropertyTransformerDescriptor,
) : PropertyBinding {
    override fun toMeta(): Meta = serializableToMeta(serializer(), this)
}


/**
 * A helper class to collect multiple bindings for a single child device.
 * This will be part of the child's configuration within the parent's spec.
 */
@Serializable
public data class ChildPropertyBindings(
    val bindings: List<PropertyBinding>,
)