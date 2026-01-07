package space.kscience.controls.composite.dsl.properties

import space.kscience.controls.composite.dsl.DeviceSpecification
import space.kscience.controls.composite.dsl.StatePropertyDelegate
import space.kscience.controls.core.legacy_alpha_2.contracts.Device
import space.kscience.controls.core.runtime.CompositeDeviceContext
import space.kscience.controls.core.legacy_alpha_2.state.StatefulDevice
import space.kscience.dataforge.meta.MetaConverter

/**
 * Creates a delegate for a mutable, logical property of type [String].
 * @see stateProperty
 */
public fun <D> DeviceSpecification<D>.stateString(
    initialValue: String,
    descriptorBuilder: PropertyDescriptorBuilder.() -> Unit = {},
): StatePropertyDelegate<D, String> where D : Device, D : CompositeDeviceContext, D : StatefulDevice =
    stateProperty(MetaConverter.string, initialValue, descriptorBuilder)

/**
 * Creates a delegate for a mutable, logical property of type [Double].
 * @see stateProperty
 */
public fun <D> DeviceSpecification<D>.stateDouble(
    initialValue: Double,
    descriptorBuilder: PropertyDescriptorBuilder.() -> Unit = {},
): StatePropertyDelegate<D, Double> where D : Device, D : CompositeDeviceContext, D : StatefulDevice =
    stateProperty(MetaConverter.double, initialValue, descriptorBuilder)

/**
 * Creates a delegate for a mutable, logical property of type [Int].
 * @see stateProperty
 */
public fun <D> DeviceSpecification<D>.stateInt(
    initialValue: Int,
    descriptorBuilder: PropertyDescriptorBuilder.() -> Unit = {},
): StatePropertyDelegate<D, Int> where D : Device, D : CompositeDeviceContext, D : StatefulDevice =
    stateProperty(MetaConverter.int, initialValue, descriptorBuilder)

/**
 * Creates a delegate for a mutable, logical property of type [Boolean].
 * @see stateProperty
 */
public fun <D> DeviceSpecification<D>.stateBoolean(
    initialValue: Boolean,
    descriptorBuilder: PropertyDescriptorBuilder.() -> Unit = {},
): StatePropertyDelegate<D, Boolean> where D : Device, D : CompositeDeviceContext, D : StatefulDevice =
    stateProperty(MetaConverter.boolean, initialValue, descriptorBuilder)

/**
 * Creates a delegate for a mutable, logical property of an enum type.
 * @see stateProperty
 */
public inline fun <reified E : Enum<E>, D> DeviceSpecification<D>.stateEnum(
    initialValue: E,
    noinline descriptorBuilder: PropertyDescriptorBuilder.() -> Unit = {},
): StatePropertyDelegate<D, E> where D : Device, D : CompositeDeviceContext, D : StatefulDevice =
    stateProperty(MetaConverter.enum(), initialValue, descriptorBuilder)