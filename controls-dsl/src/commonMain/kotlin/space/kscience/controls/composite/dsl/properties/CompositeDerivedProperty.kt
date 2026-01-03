package space.kscience.controls.composite.dsl.properties

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import space.kscience.controls.composite.dsl.CompositeSpecBuilder
import space.kscience.controls.composite.dsl.DeviceSpecification
import space.kscience.controls.core.InternalControlsApi
import space.kscience.controls.core.contracts.Device
import space.kscience.controls.core.runtime.CompositeDeviceContext
import space.kscience.controls.core.runtime.HydratableDeviceState
import space.kscience.controls.core.meta.DevicePropertySpec
import space.kscience.controls.api.descriptors.PropertyKind
import space.kscience.controls.api.data.Quality
import space.kscience.controls.api.data.StateValue
import space.kscience.controls.api.data.okState
import space.kscience.controls.core.state.DeviceState
import space.kscience.dataforge.context.error
import space.kscience.dataforge.context.logger
import space.kscience.dataforge.meta.MetaConverter
import space.kscience.dataforge.misc.DFExperimental
import space.kscience.dataforge.names.Name
import kotlin.properties.PropertyDelegateProvider
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.typeOf

/**
 * The internal, non-type-safe implementation for the `derived` property delegate.
 * This function handles the core logic of registering the property specification and its hydration logic.
 * Public, type-safe overloads delegate to this function.
 * It is marked as `@PublishedApi` to be accessible from public `inline` functions.
 */
@DFExperimental
@OptIn(ExperimentalCoroutinesApi::class, InternalControlsApi::class)
@PublishedApi
internal inline fun <D : Device, reified T : Any> DeviceSpecification<D>.internalDerived(
    converter: MetaConverter<T>,
    dependencies: Array<out DevicePropertySpec<D, *>>,
    initialValue: T,
    noinline descriptorBuilder: PropertyDescriptorBuilder.() -> Unit = {},
    noinline read: suspend D.(values: List<Any?>) -> T,
): PropertyDelegateProvider<DeviceSpecification<D>, ReadOnlyProperty<DeviceSpecification<D>, DevicePropertySpec<D, T>>> {

    // This is the delegate provider for the *public property spec*.
    // It uses the central factory to create and register the DevicePropertySpec.
    val specDelegateProvider = createPropertyDelegateProvider<D, T>(
        specProvider = {},
        name = null, // Name will be inferred from the property
        kind = PropertyKind.DERIVED,
        mutable = false,
        converter = converter,
        descriptorBuilder = descriptorBuilder,
        read = {
            error(
                "Property is a derived property. " +
                        "Its value must be read from the device's reactive state, not via a direct driver call."
            )
        },
        write = null
    )

    // This outer provider wraps the spec provider to add the hydration logic.
    return PropertyDelegateProvider { thisRef, property ->
        // 1. Get the DevicePropertySpec using the delegate created above.
        //    This step also registers the property spec within the DeviceSpecification.
        val spec = specDelegateProvider.provideDelegate(thisRef, property).getValue(thisRef, property)

        // 2. Now that the spec is created and registered, create the hydration logic for it.
        val hydrator = HydratableDeviceState<D, T> { device ->
            val deviceContext = (device as? CompositeDeviceContext)
                ?: error("Device must implement CompositeDeviceContext to hydrate a derived state.")

            val flows: List<Flow<StateValue<*>>> = dependencies.map { depSpec ->
                deviceContext.getState(depSpec).stateFlow
            }

            combine(flows) { states ->
                val values = states.map { it.value }
                try {
                    val computedValue = device.read(values)
                    val combinedTimestamp = states.maxOfOrNull { it.timestamp } ?: device.clock.now()
                    val combinedQuality =
                        if (states.all { it.quality == Quality.OK }) Quality.OK else Quality.INVALID
                    StateValue(computedValue, combinedTimestamp, combinedQuality)
                } catch (e: Exception) {
                    device.context.logger.error(e) { "Failed to compute derived property '${spec.name}'" }
                    StateValue(
                        initialValue,
                        states.maxOfOrNull { it.timestamp } ?: device.clock.now(),
                        Quality.ERROR
                    )
                }
            }.stateIn(
                scope = device,
                started = SharingStarted.Lazily,
                initialValue = okState(initialValue, device.clock)
            ).let { stateFlow ->
                object : DeviceState<T> {
                    override val stateValue: StateValue<T?> get() = stateFlow.value
                    override val stateFlow: StateFlow<StateValue<T?>> get() = stateFlow
                }
            }
        }

        // 3. Register the hydrator with the DeviceSpecification instance.
        thisRef.registeredHydrators[spec.name] = hydrator

        // 4. Return the delegate that provides the registered spec as a read-only property.
        ReadOnlyProperty { _, _ -> spec }
    }
}


/**
 * Creates a delegate for a read-only, derived property that has no dependencies on other properties.
 * Its value can be a constant, depend on time, or be computed from the device's internal state.
 * This property is semantically classified as [PropertyKind.DERIVED].
 *
 * @param D The type of the device contract.
 * @param R The type of this derived property's value.
 * @param converter The [MetaConverter] for the result type [R].
 * @param initialValue An initial value for the state before the first computation.
 * @param descriptorBuilder A DSL block to configure the property's descriptor.
 * @param read A suspendable lambda that computes the derived value. It has no value parameters.
 * @return A [PropertyDelegateProvider] that registers the property spec and its hydration logic.
 */
@DFExperimental
public inline fun <D : Device, reified R : Any> DeviceSpecification<D>.derived(
    converter: MetaConverter<R>,
    initialValue: R,
    noinline descriptorBuilder: PropertyDescriptorBuilder.() -> Unit = {},
    noinline read: suspend D.() -> R,
): PropertyDelegateProvider<DeviceSpecification<D>, ReadOnlyProperty<DeviceSpecification<D>, DevicePropertySpec<D, R>>> =
    internalDerived(converter, emptyArray(), initialValue, descriptorBuilder) { _ ->
        read()
    }


/**
 * Creates a delegate for a read-only, derived property whose value is computed reactively from a single source property.
 * This property is semantically classified as [PropertyKind.DERIVED].
 *
 * @param D The type of the device contract.
 * @param T1 The type of the dependency property's value.
 * @param R The type of this derived property's value.
 * @param dep1 The source [DevicePropertySpec] this property depends on.
 * @param converter The [MetaConverter] for the result type [R].
 * @param initialValue An initial value for the state before the first computation.
 * @param descriptorBuilder A DSL block to configure the property's descriptor.
 * @param read A type-safe, suspendable lambda that takes the current value of the dependency and computes the derived value.
 * @return A [PropertyDelegateProvider] that registers the property spec and its hydration logic.
 */
@DFExperimental
public inline fun <D : Device, T1, reified R : Any> DeviceSpecification<D>.derived(
    dep1: DevicePropertySpec<D, T1>,
    converter: MetaConverter<R>,
    initialValue: R,
    noinline descriptorBuilder: PropertyDescriptorBuilder.() -> Unit = {},
    noinline read: suspend D.(v1: T1?) -> R,
): PropertyDelegateProvider<DeviceSpecification<D>, ReadOnlyProperty<DeviceSpecification<D>, DevicePropertySpec<D, R>>> =
    internalDerived(converter, arrayOf(dep1), initialValue, descriptorBuilder) { values ->
        @Suppress("UNCHECKED_CAST")
        read(values[0] as T1?)
    }

/**
 * Creates a delegate for a read-only, derived property computed from two source properties.
 *
 * @param T1 The type of the first dependency's value.
 * @param T2 The type of the second dependency's value.
 * @param read A type-safe, suspendable lambda that takes the current values of both dependencies.
 * @see derived for other parameters.
 */
@DFExperimental
public inline fun <D : Device, T1, T2, reified R : Any> DeviceSpecification<D>.derived(
    dep1: DevicePropertySpec<D, T1>,
    dep2: DevicePropertySpec<D, T2>,
    converter: MetaConverter<R>,
    initialValue: R,
    noinline descriptorBuilder: PropertyDescriptorBuilder.() -> Unit = {},
    noinline read: suspend D.(v1: T1?, v2: T2?) -> R,
): PropertyDelegateProvider<DeviceSpecification<D>, ReadOnlyProperty<DeviceSpecification<D>, DevicePropertySpec<D, R>>> =
    internalDerived(converter, arrayOf(dep1, dep2), initialValue, descriptorBuilder) { values ->
        @Suppress("UNCHECKED_CAST")
        read(values[0] as T1?, values[1] as T2?)
    }

/**
 * Creates a delegate for a read-only, derived property computed from three source properties.
 * @see derived for parameters.
 */
@DFExperimental
public inline fun <D : Device, T1, T2, T3, reified R : Any> DeviceSpecification<D>.derived(
    dep1: DevicePropertySpec<D, T1>,
    dep2: DevicePropertySpec<D, T2>,
    dep3: DevicePropertySpec<D, T3>,
    converter: MetaConverter<R>,
    initialValue: R,
    noinline descriptorBuilder: PropertyDescriptorBuilder.() -> Unit = {},
    noinline read: suspend D.(v1: T1?, v2: T2?, v3: T3?) -> R,
): PropertyDelegateProvider<DeviceSpecification<D>, ReadOnlyProperty<DeviceSpecification<D>, DevicePropertySpec<D, R>>> =
    internalDerived(converter, arrayOf(dep1, dep2, dep3), initialValue, descriptorBuilder) { values ->
        @Suppress("UNCHECKED_CAST")
        read(values[0] as T1?, values[1] as T2?, values[2] as T3?)
    }

/**
 * Creates a delegate for a read-only, derived property computed from four source properties.
 * @see derived for parameters.
 */
@DFExperimental
public inline fun <D : Device, T1, T2, T3, T4, reified R : Any> DeviceSpecification<D>.derived(
    dep1: DevicePropertySpec<D, T1>,
    dep2: DevicePropertySpec<D, T2>,
    dep3: DevicePropertySpec<D, T3>,
    dep4: DevicePropertySpec<D, T4>,
    converter: MetaConverter<R>,
    initialValue: R,
    noinline descriptorBuilder: PropertyDescriptorBuilder.() -> Unit = {},
    noinline read: suspend D.(v1: T1?, v2: T2?, v3: T3?, v4: T4?) -> R,
): PropertyDelegateProvider<DeviceSpecification<D>, ReadOnlyProperty<DeviceSpecification<D>, DevicePropertySpec<D, R>>> =
    internalDerived(converter, arrayOf(dep1, dep2, dep3, dep4), initialValue, descriptorBuilder) { values ->
        @Suppress("UNCHECKED_CAST")
        read(values[0] as T1?, values[1] as T2?, values[2] as T3?, values[3] as T4?)
    }


/**
 * Creates a delegate for a read-only, derived property computed from five source properties.
 * @see derived for parameters.
 */
@DFExperimental
public inline fun <D : Device, T1, T2, T3, T4, T5, reified R : Any> DeviceSpecification<D>.derived(
    dep1: DevicePropertySpec<D, T1>,
    dep2: DevicePropertySpec<D, T2>,
    dep3: DevicePropertySpec<D, T3>,
    dep4: DevicePropertySpec<D, T4>,
    dep5: DevicePropertySpec<D, T5>,
    converter: MetaConverter<R>,
    initialValue: R,
    noinline descriptorBuilder: PropertyDescriptorBuilder.() -> Unit = {},
    noinline read: suspend D.(v1: T1?, v2: T2?, v3: T3?, v4: T4?, v5: T5?) -> R,
): PropertyDelegateProvider<DeviceSpecification<D>, ReadOnlyProperty<DeviceSpecification<D>, DevicePropertySpec<D, R>>> =
    internalDerived(converter, arrayOf(dep1, dep2, dep3, dep4, dep5), initialValue, descriptorBuilder) { values ->
        @Suppress("UNCHECKED_CAST")
        read(values[0] as T1?, values[1] as T2?, values[2] as T3?, values[3] as T4?, values[4] as T5?)
    }


/**
 * Creates a delegate for a read-only, boolean property that represents a logical predicate with no dependencies.
 * This property is semantically classified as [PropertyKind.PREDICATE].
 *
 * @param read A type-safe, suspendable lambda that returns `true` or `false`.
 * @see derived for other parameters.
 */
@DFExperimental
public fun <D : Device> DeviceSpecification<D>.predicate(
    descriptorBuilder: PropertyDescriptorBuilder.() -> Unit = {},
    read: suspend D.() -> Boolean,
): PropertyDelegateProvider<DeviceSpecification<D>, ReadOnlyProperty<DeviceSpecification<D>, DevicePropertySpec<D, Boolean>>> =
    derived(MetaConverter.boolean, false, {
        this.kind = PropertyKind.PREDICATE
        descriptorBuilder()
    }, read)


/**
 * Creates a delegate for a read-only, boolean property that represents a logical predicate computed from one source property.
 * This property is semantically classified as [PropertyKind.PREDICATE].
 *
 * @param read A type-safe, suspendable lambda that returns `true` or `false` based on the dependency's value.
 * @see derived for other parameters.
 */
@DFExperimental
public fun <D : Device, T1> DeviceSpecification<D>.predicate(
    dep1: DevicePropertySpec<D, T1>,
    descriptorBuilder: PropertyDescriptorBuilder.() -> Unit = {},
    read: suspend D.(v1: T1?) -> Boolean,
): PropertyDelegateProvider<DeviceSpecification<D>, ReadOnlyProperty<DeviceSpecification<D>, DevicePropertySpec<D, Boolean>>> =
    derived(dep1, MetaConverter.boolean, false, {
        this.kind = PropertyKind.PREDICATE
        descriptorBuilder()
    }, read)

/**
 * Creates a delegate for a predicate computed from two source properties.
 * @see predicate for other parameters.
 */
@DFExperimental
public fun <D : Device, T1, T2> DeviceSpecification<D>.predicate(
    dep1: DevicePropertySpec<D, T1>,
    dep2: DevicePropertySpec<D, T2>,
    descriptorBuilder: PropertyDescriptorBuilder.() -> Unit = {},
    read: suspend D.(v1: T1?, v2: T2?) -> Boolean,
): PropertyDelegateProvider<DeviceSpecification<D>, ReadOnlyProperty<DeviceSpecification<D>, DevicePropertySpec<D, Boolean>>> =
    derived(dep1, dep2, MetaConverter.boolean, false, {
        this.kind = PropertyKind.PREDICATE
        descriptorBuilder()
    }, read)

/**
 * Creates a delegate for a predicate computed from three source properties.
 * @see predicate for other parameters.
 */
@DFExperimental
public fun <D : Device, T1, T2, T3> DeviceSpecification<D>.predicate(
    dep1: DevicePropertySpec<D, T1>,
    dep2: DevicePropertySpec<D, T2>,
    dep3: DevicePropertySpec<D, T3>,
    descriptorBuilder: PropertyDescriptorBuilder.() -> Unit = {},
    read: suspend D.(v1: T1?, v2: T2?, v3: T3?) -> Boolean,
): PropertyDelegateProvider<DeviceSpecification<D>, ReadOnlyProperty<DeviceSpecification<D>, DevicePropertySpec<D, Boolean>>> =
    derived(dep1, dep2, dep3, MetaConverter.boolean, false, {
        this.kind = PropertyKind.PREDICATE
        descriptorBuilder()
    }, read)


/**
 * Declares and registers a read-only, boolean predicate property directly within a `CompositeSpecBuilder`.
 * This is a direct builder function, useful for creating predicates inside specification fragments or `deviceBlueprint` blocks.
 *
 * @param D The type of the device contract.
 * @param name The name of the predicate property.
 * @param dependencies A vararg list of [DevicePropertySpec] that this predicate depends on.
 * @param descriptorBuilder A DSL block to configure the predicate's descriptor.
 * @param read A suspendable lambda that takes the current values of the dependencies and returns a `Boolean`.
 * @return The created and registered [DevicePropertySpec] for the predicate.
 */
@DFExperimental
public fun <D : Device> CompositeSpecBuilder<D>.predicate(
    name: Name,
    vararg dependencies: DevicePropertySpec<in D, *>,
    descriptorBuilder: PropertyDescriptorBuilder.() -> Unit = {},
    read: suspend D.(values: List<Any?>) -> Boolean,
): DevicePropertySpec<D, Boolean> {
    val dslBuilder = PropertyDescriptorBuilder(name).apply {
        this.kind = PropertyKind.PREDICATE
        descriptorBuilder()
    }
    val descriptor = dslBuilder.build(
        mutable = false,
        valueTypeName = typeOf<Boolean>().toString()
    )

    val spec = object : DevicePropertySpec<D, Boolean> {
        override val name: Name = name
        override val descriptor = descriptor
        override val converter = MetaConverter.boolean
        override val valueType = typeOf<Boolean>()

        override suspend fun read(device: D): Boolean? {
            error(
                "Property '$name' is a derived predicate and should be read from its reactive state, not via a direct driver call."
            )
        }
    }
    registerProperty(spec)

    // Register the hydration logic for this directly-built predicate.
    @OptIn(InternalControlsApi::class)
    val hydrator = HydratableDeviceState<D, Boolean> { device ->
        val deviceContext = (device as? CompositeDeviceContext)
            ?: error("Device must implement CompositeDeviceContext to hydrate a derived state.")

        val flows: List<Flow<StateValue<*>>> = dependencies.map { depSpec ->
            deviceContext.getState(depSpec).stateFlow
        }

        combine(flows) { states ->
            val values = states.map { it.value }
            try {
                val computedValue = device.read(values)
                val combinedTimestamp = states.maxOfOrNull { it.timestamp } ?: device.clock.now()
                val combinedQuality = if (states.all { it.quality == Quality.OK }) Quality.OK else Quality.INVALID
                StateValue(computedValue, combinedTimestamp, combinedQuality)
            } catch (e: Exception) {
                device.context.logger.error(e) { "Failed to compute predicate property '$name'" }
                StateValue(false, states.maxOfOrNull { it.timestamp } ?: device.clock.now(), Quality.ERROR)
            }
        }.stateIn(
            scope = device,
            started = SharingStarted.Lazily,
            initialValue = okState(false, device.clock)
        ).let { stateFlow ->
            object : DeviceState<Boolean> {
                override val stateValue: StateValue<Boolean?> get() = stateFlow.value
                override val stateFlow: StateFlow<StateValue<Boolean?>> get() = stateFlow
            }
        }
    }

    @OptIn(InternalControlsApi::class)
    registeredHydrators[name] = hydrator

    return spec
}