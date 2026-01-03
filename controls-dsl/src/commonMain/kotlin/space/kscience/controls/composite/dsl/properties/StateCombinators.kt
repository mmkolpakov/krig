package space.kscience.controls.composite.dsl.properties

import space.kscience.controls.composite.dsl.DeviceSpecification
import space.kscience.controls.core.contracts.Device
import space.kscience.controls.core.meta.DevicePropertySpec
import space.kscience.controls.api.descriptors.PropertyKind
import space.kscience.dataforge.meta.MetaConverter
import space.kscience.dataforge.misc.DFExperimental
import kotlin.properties.PropertyDelegateProvider
import kotlin.properties.ReadOnlyProperty

/**
 * Creates a read-only derived property by transforming a single source property.
 * This is a convenience DSL function on top of the generic [derived] delegate.
 * The resulting property is semantically classified as [PropertyKind.DERIVED].
 *
 * @param D The type of the device contract.
 * @param T The type of the source property.
 * @param R The type of the resulting derived property.
 * @param source The source property specification.
 * @param initialValue The initial value for the derived property before the first computation.
 * @param converter A [MetaConverter] for the result type [R]. By default, it uses an experimental,
 *                  serialization-based converter.
 * @param descriptorBuilder A DSL block to configure the property's descriptor.
 * @param transform A suspendable lambda to transform the value from type [T] to [R].
 * @return A [PropertyDelegateProvider] that provides a [DevicePropertySpec] at build time.
 */
@OptIn(DFExperimental::class)
public inline fun <D : Device, T, reified R : Any> DeviceSpecification<D>.map(
    source: DevicePropertySpec<D, T>,
    initialValue: R,
    converter: MetaConverter<R> = MetaConverter.serializable(),
    noinline descriptorBuilder: PropertyDescriptorBuilder.() -> Unit = {},
    crossinline transform: suspend (T?) -> R,
): PropertyDelegateProvider<DeviceSpecification<D>, ReadOnlyProperty<DeviceSpecification<D>, DevicePropertySpec<D, R>>> =
    derived(
        dep1 = source,
        converter = converter,
        initialValue = initialValue,
        descriptorBuilder = descriptorBuilder
    ) { v1 ->
        transform(v1)
    }

/**
 * Creates a read-only derived property by combining two source properties.
 * This is a convenience DSL function on top of the generic [derived] delegate.
 * The resulting property is semantically classified as [PropertyKind.DERIVED].
 *
 * @param converter A [MetaConverter] for the result type [R]. By default, it uses an experimental,
 *                  serialization-based converter.
 * @param descriptorBuilder A DSL block to configure the property's descriptor.
 * @param transform A suspendable lambda to combine values from two sources into the result.
 * @return A [PropertyDelegateProvider] that provides a [DevicePropertySpec] at build time.
 */
@OptIn(DFExperimental::class)
public inline fun <D : Device, T1, T2, reified R : Any> DeviceSpecification<D>.combine(
    source1: DevicePropertySpec<D, T1>,
    source2: DevicePropertySpec<D, T2>,
    initialValue: R,
    converter: MetaConverter<R> = MetaConverter.serializable(),
    noinline descriptorBuilder: PropertyDescriptorBuilder.() -> Unit = {},
    crossinline transform: suspend (T1?, T2?) -> R,
): PropertyDelegateProvider<DeviceSpecification<D>, ReadOnlyProperty<DeviceSpecification<D>, DevicePropertySpec<D, R>>> =
    derived(
        dep1 = source1,
        dep2 = source2,
        converter = converter,
        initialValue = initialValue,
        descriptorBuilder = descriptorBuilder
    ) { v1, v2 ->
        transform(v1, v2)
    }

/**
 * Creates a read-only derived property by combining three source properties.
 * The resulting property is semantically classified as [PropertyKind.DERIVED].
 *
 * @see combine for other parameters.
 */
@OptIn(DFExperimental::class)
public inline fun <D : Device, T1, T2, T3, reified R : Any> DeviceSpecification<D>.combine(
    source1: DevicePropertySpec<D, T1>,
    source2: DevicePropertySpec<D, T2>,
    source3: DevicePropertySpec<D, T3>,
    initialValue: R,
    converter: MetaConverter<R> = MetaConverter.serializable(),
    noinline descriptorBuilder: PropertyDescriptorBuilder.() -> Unit = {},
    crossinline transform: suspend (T1?, T2?, T3?) -> R,
): PropertyDelegateProvider<DeviceSpecification<D>, ReadOnlyProperty<DeviceSpecification<D>, DevicePropertySpec<D, R>>> =
    derived(
        dep1 = source1,
        dep2 = source2,
        dep3 = source3,
        converter = converter,
        initialValue = initialValue,
        descriptorBuilder = descriptorBuilder
    ) { v1, v2, v3 ->
        transform(v1, v2, v3)
    }

/**
 * Creates a read-only derived property by combining four source properties.
 * The resulting property is semantically classified as [PropertyKind.DERIVED].
 *
 * @see combine for other parameters.
 */
@OptIn(DFExperimental::class)
public inline fun <D : Device, T1, T2, T3, T4, reified R : Any> DeviceSpecification<D>.combine(
    source1: DevicePropertySpec<D, T1>,
    source2: DevicePropertySpec<D, T2>,
    source3: DevicePropertySpec<D, T3>,
    source4: DevicePropertySpec<D, T4>,
    initialValue: R,
    converter: MetaConverter<R> = MetaConverter.serializable(),
    noinline descriptorBuilder: PropertyDescriptorBuilder.() -> Unit = {},
    crossinline transform: suspend (T1?, T2?, T3?, T4?) -> R,
): PropertyDelegateProvider<DeviceSpecification<D>, ReadOnlyProperty<DeviceSpecification<D>, DevicePropertySpec<D, R>>> =
    derived(
        dep1 = source1,
        dep2 = source2,
        dep3 = source3,
        dep4 = source4,
        converter = converter,
        initialValue = initialValue,
        descriptorBuilder = descriptorBuilder
    ) { v1, v2, v3, v4 ->
        transform(v1, v2, v3, v4)
    }

/**
 * Creates a read-only derived property by combining five source properties.
 * The resulting property is semantically classified as [PropertyKind.DERIVED].
 *
 * @see combine for other parameters.
 */
@OptIn(DFExperimental::class)
public inline fun <D : Device, T1, T2, T3, T4, T5, reified R : Any> DeviceSpecification<D>.combine(
    source1: DevicePropertySpec<D, T1>,
    source2: DevicePropertySpec<D, T2>,
    source3: DevicePropertySpec<D, T3>,
    source4: DevicePropertySpec<D, T4>,
    source5: DevicePropertySpec<D, T5>,
    initialValue: R,
    converter: MetaConverter<R> = MetaConverter.serializable(),
    noinline descriptorBuilder: PropertyDescriptorBuilder.() -> Unit = {},
    crossinline transform: suspend (T1?, T2?, T3?, T4?, T5?) -> R,
): PropertyDelegateProvider<DeviceSpecification<D>, ReadOnlyProperty<DeviceSpecification<D>, DevicePropertySpec<D, R>>> =
    derived(
        dep1 = source1,
        dep2 = source2,
        dep3 = source3,
        dep4 = source4,
        dep5 = source5,
        converter = converter,
        initialValue = initialValue,
        descriptorBuilder = descriptorBuilder
    ) { v1, v2, v3, v4, v5 ->
        transform(v1, v2, v3, v4, v5)
    }

/**
 * Creates a read-only derived property by reducing a list of source properties of the same type to a single value.
 * This is the primary mechanism for creating aggregate or summary properties (e.g., calculating an average).
 * This function is **type-safe** as it operates on a homogeneous list of dependencies.
 * The resulting property is semantically classified as [PropertyKind.DERIVED].
 *
 * @param D The type of the device contract.
 * @param T The type of the source properties.
 * @param R The type of the resulting reduced property.
 * @param sources A `vararg` array of source properties to be reduced.
 * @param initialValue The initial value for the reduced property.
 * @param converter A [MetaConverter] for the result type [R].
 * @param descriptorBuilder A DSL block to configure the property's descriptor.
 * @param reduce A suspendable lambda that takes the device instance and a list of nullable values from the source
 *               properties and computes the single reduced value.
 * @return A [PropertyDelegateProvider] that provides a [DevicePropertySpec] at build time.
 */
@OptIn(DFExperimental::class)
public inline fun <D : Device, T, reified R : Any> DeviceSpecification<D>.reduce(
    vararg sources: DevicePropertySpec<in D, T>,
    initialValue: R,
    converter: MetaConverter<R> = MetaConverter.serializable(),
    noinline descriptorBuilder: PropertyDescriptorBuilder.() -> Unit = {},
    crossinline reduce: suspend D.(values: List<T?>) -> R,
): PropertyDelegateProvider<DeviceSpecification<D>, ReadOnlyProperty<DeviceSpecification<D>, DevicePropertySpec<D, R>>> =
    internalDerived(
        converter = converter,
        dependencies = sources,
        initialValue = initialValue,
        descriptorBuilder = descriptorBuilder
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        this.reduce(values as List<T?>)
    }

/**
 * An advanced, less type-safe delegate for creating a derived property from an arbitrary number of heterogeneous sources.
 * Unlike the `combine` overloads, this function accepts a `List` of `DevicePropertySpec<*, *>`.
 *
 * **Warning:** The `read` lambda for this function receives a `List<Any?>`. It is the developer's responsibility
 * to ensure the correct order of dependencies and perform safe casts. This function should only be used when
 * the number of dependencies exceeds the available type-safe `combine` overloads (currently 5).
 *
 * @param dependencies A `List` of source property specifications.
 * @see derived for other parameters.
 */
@OptIn(DFExperimental::class)
public inline fun <D : Device, reified R : Any> DeviceSpecification<D>.combine(
    dependencies: List<DevicePropertySpec<D, *>>,
    initialValue: R,
    converter: MetaConverter<R> = MetaConverter.serializable(),
    noinline descriptorBuilder: PropertyDescriptorBuilder.() -> Unit = {},
    crossinline transform: suspend D.(List<Any?>) -> R,
): PropertyDelegateProvider<DeviceSpecification<D>, ReadOnlyProperty<DeviceSpecification<D>, DevicePropertySpec<D, R>>> =
    internalDerived(
        converter = converter,
        dependencies = dependencies.toTypedArray(),
        initialValue = initialValue,
        descriptorBuilder = descriptorBuilder
    ) { values ->
        this.transform(values)
    }