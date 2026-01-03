package space.kscience.controls.composite.dsl.properties

import space.kscience.controls.composite.dsl.DeviceSpecification
import space.kscience.controls.composite.dsl.StatePropertyDelegate
import space.kscience.controls.composite.persistence.StatefulFeature
import space.kscience.controls.core.contracts.Device
import space.kscience.controls.core.runtime.CompositeDeviceContext
import space.kscience.controls.core.meta.MutableDevicePropertySpec
import space.kscience.controls.core.descriptors.PropertyKind
import space.kscience.controls.core.state.MutableDeviceState
import space.kscience.controls.core.state.StatefulDevice
import space.kscience.controls.core.state.value
import space.kscience.dataforge.meta.MetaConverter
import space.kscience.dataforge.names.parseAsName
import kotlin.properties.PropertyDelegateProvider
import kotlin.properties.ReadOnlyProperty

/**
 * Creates a delegate for a mutable, logical property that serves as both the public API
 * and the internal, persistent state. This delegate unifies the concepts of a public mutable property
 * and an internal, stateful, reactive value.
 *
 * The property is automatically registered for persistence by default (by setting `persistent = true`
 * in its descriptor) and is assigned the [PropertyKind.LOGICAL]. The runtime implementation relies
 * on this property to correctly snapshot and restore the device's state. It provides a lock-free,
 * thread-safe, instance-specific state.
 *
 * This function uses a nested [PropertyDelegateProvider] structure to separate build-time configuration
 * from runtime state provision:
 * 1. The outer provider registers the public-facing [MutableDevicePropertySpec] when the `DeviceSpecification` is created.
 * 2. It returns a read-only property holding the inner provider.
 * 3. The inner provider is used by the runtime on a device instance (`D`) to create or retrieve the actual [MutableDeviceState].
 *
 * @param D The type of the device, must extend [Device], [CompositeDeviceContext] and [StatefulDevice].
 * @param T The type of the property value.
 * @param converter The [MetaConverter] for serialization/deserialization.
 * @param initialValue The initial value of the property.
 * @param descriptorBuilder An optional DSL block to configure the property's descriptor.
 * @return A [PropertyDelegateProvider] that registers the public property and provides a nested
 *         [PropertyDelegateProvider] for the runtime state delegate.
 */
public inline fun <reified T, D> DeviceSpecification<D>.stateProperty(
    converter: MetaConverter<T>,
    initialValue: T,
    noinline descriptorBuilder: PropertyDescriptorBuilder.() -> Unit = {},
): StatePropertyDelegate<D, T> where D : Device, D : CompositeDeviceContext, D : StatefulDevice {
    return PropertyDelegateProvider { thisRef, property ->
        thisRef.registerFeature(StatefulFeature())

        lateinit var spec: MutableDevicePropertySpec<D, T>

        // Eagerly register the public-facing property specification at build-time.
        val publicPropertyProvider = thisRef.mutableProperty(
            converter = converter,
            descriptorBuilder = {
                this.kind = PropertyKind.LOGICAL
                persistent = true // Stateful properties are persistent by default.
                descriptorBuilder()
            },
            name = property.name.parseAsName(),
            read = {
                val context = (this as? CompositeDeviceContext)
                    ?: error("Device must implement CompositeDeviceContext to use stateful properties.")
                context.getMutableState(spec).value
            },
            write = { value ->
                val context = (this as? CompositeDeviceContext)
                    ?: error("Device must implement CompositeDeviceContext to use stateful properties.")
                context.getMutableState(spec).update(value)
            }
        )
        // This call registers the public spec.
        spec = publicPropertyProvider.provideDelegate(thisRef, property).getValue(thisRef, property)

        // This is the delegate that will be used at runtime inside the device instance.
        val runtimeDelegateProvider = PropertyDelegateProvider<Any?, ReadOnlyProperty<D, MutableDeviceState<T>>> { _, runtimeProp ->
            ReadOnlyProperty { thisRefRuntime, _ ->
//                thisRefRuntime.statefulLogic.getOrPutState(runtimeProp.name) {
//                    val newState = VirtualMutableDeviceState(initialValue)
//                    // Automatically mark the device as dirty when the state changes.
//                    newState.onChange(thisRefRuntime) { _, _ ->
//                        thisRefRuntime.markDirty()
//                    }
//                    // Register the stateful element for snapshotting.
//                    thisRefRuntime.registerElement(
//                        StatefulDelegateElement(runtimeProp.name, newState, converter)
//                    )
//
//                    newState
//                }
                TODO("StatefulDevice simplified now")
            }
        }

        // Return a read-only property that holds the runtime delegate provider.
        ReadOnlyProperty { _, _ -> runtimeDelegateProvider }
    }
}