package space.kscience.controls.core.meta

import space.kscience.dataforge.meta.*
import space.kscience.dataforge.meta.descriptors.MetaDescriptor
import space.kscience.dataforge.meta.descriptors.MetaDescriptorBuilder
import space.kscience.dataforge.misc.DFExperimental
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName
import kotlin.properties.PropertyDelegateProvider
import kotlin.properties.ReadOnlyProperty

/**
 * A specialized extension of DataForge's [MetaSpec] designed to declaratively define the output
 * structure of a [DeviceActionSpec]. It provides a mechanism for creating type-safe references ([MetaRef])
 * to the fields within an action's result [Meta].
 *
 * This class is the cornerstone for enabling type-safe data flow between steps in a [TransactionPlan].
 * The `item` property delegate is the primary factory for creating and registering output properties.
 */
@DFExperimental
public abstract class ActionOutputSpec : MetaSpec() {

    /**
     * The primary factory for creating and registering a [MetaRef] for an output property.
     * It allows defining the converter, internal path (key), and a descriptor for the property.
     *
     * @param T The type of the value being referenced.
     * @param converter The [MetaConverter] for type `T`.
     * @param key The internal DataForge [Name] path for this field within the action's result. Defaults to the property name.
     * @param descriptorBuilder An optional DSL block to configure the [MetaDescriptor] for this field.
     * @return A [PropertyDelegateProvider] that creates the [MetaRef].
     */
    public fun <T> item(
        converter: MetaConverter<T>,
        key: Name? = null,
        descriptorBuilder: MetaDescriptorBuilder.() -> Unit = {},
    ): PropertyDelegateProvider<ActionOutputSpec, ReadOnlyProperty<ActionOutputSpec, MetaRef<T>>> {
        val finalDescriptor = MetaDescriptor {
            // Apply the descriptor from the converter first as a base
            converter.descriptor?.let { from(it) }
            // Then apply custom modifications from the builder
            descriptorBuilder()
        }

        return PropertyDelegateProvider { _, property ->
            val relativeName = key ?: property.name.asName()
            val ref = MetaRef(relativeName, converter, finalDescriptor)
            registerRef(ref)
            ReadOnlyProperty { _, _ -> ref }
        }
    }
}

/**
 * A convenience delegate for a [String] output field.
 */
@DFExperimental
public fun ActionOutputSpec.string(
    key: Name? = null,
    descriptorBuilder: MetaDescriptorBuilder.() -> Unit = {},
): PropertyDelegateProvider<ActionOutputSpec, ReadOnlyProperty<ActionOutputSpec, MetaRef<String>>> =
    item(MetaConverter.string, key, descriptorBuilder)

/**
 * A convenience delegate for a [Boolean] output field.
 */
@DFExperimental
public fun ActionOutputSpec.boolean(
    key: Name? = null,
    descriptorBuilder: MetaDescriptorBuilder.() -> Unit = {},
): PropertyDelegateProvider<ActionOutputSpec, ReadOnlyProperty<ActionOutputSpec, MetaRef<Boolean>>> =
    item(MetaConverter.boolean, key, descriptorBuilder)

/**
 * A convenience delegate for a [Number] output field.
 */
@DFExperimental
public fun ActionOutputSpec.number(
    key: Name? = null,
    descriptorBuilder: MetaDescriptorBuilder.() -> Unit = {},
): PropertyDelegateProvider<ActionOutputSpec, ReadOnlyProperty<ActionOutputSpec, MetaRef<Number>>> =
    item(MetaConverter.number, key, descriptorBuilder)

/**
 * A convenience delegate for a [Meta] output field.
 */
@DFExperimental
public fun ActionOutputSpec.metaItem(
    key: Name? = null,
    descriptorBuilder: MetaDescriptorBuilder.() -> Unit = {},
): PropertyDelegateProvider<ActionOutputSpec, ReadOnlyProperty<ActionOutputSpec, MetaRef<Meta>>> =
    item(MetaConverter.meta, key, descriptorBuilder)