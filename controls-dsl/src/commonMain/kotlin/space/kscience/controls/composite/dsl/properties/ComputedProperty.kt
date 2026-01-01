package space.kscience.controls.composite.dsl.properties

import space.kscience.controls.composite.dsl.DeviceSpecification
import space.kscience.controls.core.contracts.Device
import space.kscience.controls.core.meta.DevicePropertySpec
import space.kscience.controls.core.descriptors.PropertyDescriptor
import space.kscience.controls.core.runtime.CompositeDeviceContext
import space.kscience.controls.core.descriptors.PropertyKind
import space.kscience.controls.core.state.DeviceState
import space.kscience.dataforge.meta.MetaConverter
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName
import kotlin.properties.PropertyDelegateProvider
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KType
import kotlin.reflect.typeOf

/**
 * Internal implementation for the logicalProperty delegate.
 * It registers the property specification at build time and provides a delegate
 * that resolves to a [DeviceState] at runtime.
 */
@PublishedApi
internal fun <D : Device, T> DeviceSpecification<D>.internalComputedProperty(
    converter: MetaConverter<T>,
    valueType: KType,
    descriptorBuilder: PropertyDescriptorBuilder.() -> Unit = {},
    name: Name? = null,
    read: suspend D.() -> T?,
): ReadOnlyProperty<DeviceSpecification<D>, PropertyDelegateProvider<Any?, ReadOnlyProperty<CompositeDeviceContext, DeviceState<T>>>> {
    return ReadOnlyProperty { thisRef, property ->
        // At build time, register the public property specification.
        val propertyName = name ?: property.name.asName()
        val dslBuilder = PropertyDescriptorBuilder(propertyName).apply {
            this.kind = PropertyKind.DERIVED
            descriptorBuilder()
        }
        val descriptor = dslBuilder.build(mutable = false, valueTypeName = valueType.toString())

        val spec = object : DevicePropertySpec<D, T> {
            override val name: Name = propertyName
            override val descriptor: PropertyDescriptor = descriptor
            override val converter: MetaConverter<T> = converter
            override val valueType: KType = valueType
            override suspend fun read(device: D): T? = device.read()
        }
        thisRef.registerPropertySpec(spec)

        // Return a provider for the runtime delegate
        PropertyDelegateProvider { _, _ ->
            // At runtime, this delegate will resolve the corresponding DeviceState.
            ReadOnlyProperty { thisRefRuntime, _ ->
                thisRefRuntime.getState(spec)
            }
        }
    }
}


/**
 * A property delegate that simultaneously declares a public, read-only device property
 * and provides a reactive [DeviceState] for internal use within the device's logic.
 *
 * This delegate should be used to expose a logical property as part of the device's public API.
 * The value can be derived from internal state (`stateful` properties), child properties, or direct driver calls.
 *
 * The host device for this delegate must implement [CompositeDeviceContext].
 *
 * @param converter The [MetaConverter] for the property's value type.
 * @param descriptorBuilder A DSL block to configure the property's [PropertyDescriptor].
 * @param read A suspendable lambda defining how to compute the property's value.
 */
public inline fun <D : Device, reified T> DeviceSpecification<D>.computedProperty(
    converter: MetaConverter<T>,
    noinline descriptorBuilder: PropertyDescriptorBuilder.() -> Unit = {},
    name: Name? = null,
    noinline read: suspend D.() -> T?,
): ReadOnlyProperty<DeviceSpecification<D>, PropertyDelegateProvider<Any?, ReadOnlyProperty<CompositeDeviceContext, DeviceState<T>>>> =
    internalComputedProperty(converter, typeOf<T>(), descriptorBuilder, name, read)
