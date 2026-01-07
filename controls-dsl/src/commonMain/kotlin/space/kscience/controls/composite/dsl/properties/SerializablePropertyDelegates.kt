@file:OptIn(DFExperimental::class)

package space.kscience.controls.composite.dsl.properties

import space.kscience.controls.composite.dsl.DeviceSpecification
import space.kscience.controls.core.legacy_alpha_2.contracts.Device
import space.kscience.controls.core.legacy_alpha_2.meta.DevicePropertySpec
import space.kscience.controls.core.legacy_alpha_2.meta.MutableDevicePropertySpec
import space.kscience.dataforge.meta.MetaConverter
import space.kscience.dataforge.misc.DFExperimental
import space.kscience.dataforge.names.Name
import kotlin.properties.PropertyDelegateProvider
import kotlin.properties.ReadOnlyProperty

/**
 * Creates a delegate for a read-only property of a `@Serializable` type, automatically inferring
 * its `MetaConverter` using `kotlinx.serialization`. This delegate is a convenience wrapper
 * around the generic `property` delegate, reducing boilerplate code.
 *
 * ### Example:
 * ```kotlin
 * @Serializable
 * data class Measurement(val value: Double, val error: Double)
 *
 * // Before: Manual converter specification
 * val measurement by property(
 *     converter = MetaConverter.serializable<Measurement>(),
 *     read = { ... }
 * )
 *
 * // After: Converter is inferred automatically
 * val measurement by serializableProperty<_, Measurement>(read = { ... })
 * ```
 *
 * @param T The `@Serializable` type of the property value.
 * @param D The type of the device contract.
 * @param descriptorBuilder A DSL block to configure the property's [PropertyDescriptor].
 * @param name An optional explicit name for the property. If not provided, the delegated property name is used.
 * @param read The suspendable logic for reading the property's value from a device instance.
 * @return A [PropertyDelegateProvider] that registers the property spec and its hydration logic.
 * @see space.kscience.controls.composite.dsl.properties.property
 */
public inline fun <D : Device, reified T> DeviceSpecification<D>.serializableProperty(
    noinline descriptorBuilder: PropertyDescriptorBuilder.() -> Unit = {},
    name: Name? = null,
    noinline read: suspend D.() -> T?,
): PropertyDelegateProvider<DeviceSpecification<D>, ReadOnlyProperty<DeviceSpecification<D>, DevicePropertySpec<D, T>>> = property(
    converter = MetaConverter.serializable(),
    descriptorBuilder = descriptorBuilder,
    name = name,
    read = read
)

/**
 * Creates a delegate for a mutable property of a `@Serializable` type, automatically inferring
 * its `MetaConverter` using `kotlinx.serialization`. This delegate is a convenience wrapper
 * around the generic `mutableProperty` delegate.
 *
 * @param T The `@Serializable` type of the property value.
 * @param D The type of the device contract.
 * @param descriptorBuilder A DSL block to configure the property's [PropertyDescriptor].
 * @param name An optional explicit name for the property. If not provided, the delegated property name is used.
 * @param read The suspendable logic for reading the property's value from a device instance.
 * @param write The suspendable logic for writing a new value.
 * @return A [PropertyDelegateProvider] that registers the property spec and its hydration logic.
 * @see space.kscience.controls.composite.dsl.properties.mutableProperty
 */
public inline fun <D : Device, reified T> DeviceSpecification<D>.mutableSerializableProperty(
    noinline descriptorBuilder: PropertyDescriptorBuilder.() -> Unit = {},
    name: Name? = null,
    noinline read: suspend D.() -> T?,
    noinline write: suspend D.(value: T) -> Unit,
): PropertyDelegateProvider<DeviceSpecification<D>, ReadOnlyProperty<DeviceSpecification<D>, MutableDevicePropertySpec<D, T>>> = mutableProperty(
    converter = MetaConverter.serializable(),
    descriptorBuilder = descriptorBuilder,
    name = name,
    read = read,
    write = write
)