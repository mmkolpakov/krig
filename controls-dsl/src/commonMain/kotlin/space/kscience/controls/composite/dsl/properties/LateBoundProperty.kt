package space.kscience.controls.composite.dsl.properties

import space.kscience.controls.composite.dsl.DeviceSpecification
import space.kscience.controls.core.legacy_alpha_2.contracts.Device
import space.kscience.controls.core.legacy_alpha_2.state.LateBindableDeviceState
import space.kscience.controls.api.data.okState
import space.kscience.controls.core.legacy_alpha_2.state.value
import space.kscience.dataforge.meta.MetaConverter
import space.kscience.dataforge.names.asName
import kotlin.properties.PropertyDelegateProvider
import kotlin.properties.ReadOnlyProperty

/**
 * Creates a delegate for a property whose state can be bound later.
 * This is essential for creating models with circular dependencies.
 * The property is registered as a regular mutable property, but its underlying state is a [LateBindableDeviceState].
 *
 * This function uses [PropertyDelegateProvider] to ensure the public-facing mutable property
 * is registered at the moment of declaration.
 *
 * @param T The type of the property value.
 * @param converter The [MetaConverter] for serialization.
 * @param initialValue The value the property will hold until it is bound.
 * @param descriptorBuilder A DSL block to configure the property's descriptor.
 * @return A [PropertyDelegateProvider] that registers the public property and provides the [LateBindableDeviceState] for internal use.
 */
public inline fun <D : Device, reified T> DeviceSpecification<D>.lateBoundProperty(
    converter: MetaConverter<T>,
    initialValue: T,
    noinline descriptorBuilder: PropertyDescriptorBuilder.() -> Unit = {},
): PropertyDelegateProvider<DeviceSpecification<D>, ReadOnlyProperty<DeviceSpecification<D>, LateBindableDeviceState<T>>> =
    PropertyDelegateProvider { thisRef, property ->
        val state = LateBindableDeviceState(okState(initialValue))

        val publicPropertyProvider = thisRef.mutableProperty(
            converter = converter,
            descriptorBuilder = descriptorBuilder,
            name = property.name.asName(),
            read = { state.value },
            write = { value -> state.update(value) }
        )
        publicPropertyProvider.provideDelegate(thisRef, property)
        ReadOnlyProperty { _, _ -> state }
    }