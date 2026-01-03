package space.kscience.controls.composite.dsl.children

import space.kscience.controls.composite.dsl.CompositeSpecDsl
import space.kscience.controls.core.contracts.Device
import space.kscience.controls.fsm.DeviceLifecycleConfig
import space.kscience.controls.connectivity.ChildPropertyBindings
import space.kscience.controls.connectivity.ConstPropertyBinding
import space.kscience.controls.connectivity.ParentPropertyBinding
import space.kscience.controls.connectivity.PropertyBinding
import space.kscience.controls.connectivity.PropertyTransformerDescriptor
import space.kscience.controls.connectivity.TransformedPropertyBinding
import space.kscience.controls.core.meta.DevicePropertySpec
import space.kscience.controls.core.meta.MutableDevicePropertySpec
import space.kscience.controls.core.features.Feature
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.MutableMeta
import kotlin.contracts.ExperimentalContracts

/**
 * A DSL marker for child device configuration.
 */
@DslMarker
public annotation class ChildConfigDsl

/**
 * A configuration object returned by [bindsTo] to allow for optional transformation via [using].
 * Annotated with `@CompositeSpecDsl` to ensure proper receiver scoping.
 * @param P The parent device type.
 * @param C The child device type.
 * @param S The source property type.
 * @param T The target property type.
 */
@CompositeSpecDsl
@ChildConfigDsl
public class BindingConfigurator<P : Device, C : Device, S, T>(
    private val bindingBuilder: PropertyBindingBuilder<P, C>,
    internal val sourceSpec: DevicePropertySpec<P, S>,
    internal val targetSpec: MutableDevicePropertySpec<C, T>,
) {
    /**
     * Applies a serializable transformation descriptor to the binding. The runtime will resolve this
     * descriptor to a transformation logic instance and use it to convert the source property's value
     * before updating the target property.
     *
     * This replaces the default direct binding with a [space.kscience.controls.connectivity.TransformedPropertyBinding].
     *
     * Example:
     * ```
     * child.scaledValue bindsTo parent.rawValue using LinearTransformDescriptor(scale = 1.5)
     * ```
     *
     * @param transformer The serializable [space.kscience.controls.connectivity.PropertyTransformerDescriptor] instance describing the conversion.
     */
    public infix fun using(transformer: PropertyTransformerDescriptor) {
        val bindingToRemove = ParentPropertyBinding(targetSpec.name, sourceSpec.name)
        bindingBuilder.bindings.remove(bindingToRemove)
        bindingBuilder.bindings.add(TransformedPropertyBinding(targetSpec.name, sourceSpec.name, transformer))
    }
}


/**
 * A DSL builder for creating a list of [space.kscience.controls.connectivity.PropertyBinding] for a child device.
 * Annotated with `@CompositeSpecDsl` to ensure proper receiver scoping.
 * @param P The type of the parent device.
 * @param C The type of the child device.
 */
@CompositeSpecDsl
@ChildConfigDsl
public class PropertyBindingBuilder<P : Device, C : Device> {
    internal val bindings = mutableListOf<PropertyBinding>()

    /**
     * Binds a child property to a constant value. The value is converted to [Meta]
     * using the property's [MetaConverter]. This operation is type-safe and only allows
     * binding to mutable properties.
     *
     * @param target The **mutable** property on the child device.
     * @param value The constant value to set.
     */
    public fun <T> bind(target: MutableDevicePropertySpec<C, T>, value: T) {
        val meta = target.converter.convert(value)
        bindings.add(ConstPropertyBinding(target.name, meta))
    }

    /**
     * A type-safe, infix way to declare a binding from a parent property to a child property.
     * The target property must be mutable. This function creates a default direct binding and
     * returns a [BindingConfigurator] to allow for an optional transformation via the `using` infix function.
     *
     * Usage:
     * ```
     * child.target bindsTo parent.masterTarget
     * child.stringValue bindsTo parent.doubleValue using ToStringTransformer
     * child.scaledValue bindsTo parent.rawValue using LinearTransform(scale = 1.5)
     * ```
     */
    public infix fun <S> MutableDevicePropertySpec<C, S>.bindsTo(
        source: DevicePropertySpec<P, S>,
    ): BindingConfigurator<P, C, S, S> {
        // Add a provisional direct binding. It will be removed if `using` is called.
        bindings.add(ParentPropertyBinding(this.name, source.name))
        return BindingConfigurator(this@PropertyBindingBuilder, source, this)
    }
}


/**
 * A builder for configuring a child component, including its lifecycle, metadata overrides, and property bindings.
 * Annotated with `@CompositeSpecDsl` to ensure proper receiver scoping.
 * @param P The parent device type.
 * @param C The child device type.
 */
@OptIn(ExperimentalContracts::class)
@CompositeSpecDsl
@ChildConfigDsl
public class ChildConfigBuilder<P : Device, C : Device> {
    public var lifecycle: DeviceLifecycleConfig = DeviceLifecycleConfig()
    public var meta: Meta = Meta.EMPTY
    public val features: MutableSet<Feature> = mutableSetOf()
    private val bindingsBuilder = PropertyBindingBuilder<P, C>()

    /**
     * Configures the lifecycle for the child device using a type-safe DSL block.
     * This block operates on an instance of [DeviceLifecycleConfig], which is a [Scheme].
     */
    public fun lifecycle(block: DeviceLifecycleConfig.() -> Unit) {
        this.lifecycle = DeviceLifecycleConfig(block)
    }

    /**
     * Configures declarative property bindings for the child device.
     * This block allows both direct `bind` calls and usage of the `bindsTo` infix function.
     *
     * Example:
     * ```
     * bindings {
     *     // Bind to a constant value
     *     bind(child.enabled, true)
     *
     *     // Bind to a parent property
     *     child.target bindsTo parent.masterTarget
     *
     *     // Bind with transformation
     *     child.displayValue bindsTo parent.numericValue using ToStringTransformer
     * }
     * ```
     */
    public fun bindings(block: PropertyBindingBuilder<P, C>.() -> Unit) {
        bindingsBuilder.apply(block)
    }

    /**
     * Configures the metadata for this specific child instance. This metadata will be layered on top of
     * the blueprint's metadata and below any runtime attachment metadata.
     *
     * Because this builder is annotated with `@CompositeSpecDsl`, this `meta` function shadows the one
     * from the parent `CompositeSpecBuilder`, ensuring that metadata is applied to the child, not the parent blueprint.
     * To access the parent's `meta` block, use a qualified `this`: `this@CompositeSpecBuilder.meta { ... }`.
     */
    public fun meta(block: MutableMeta.() -> Unit) {
        this.meta = Meta(block)
    }

    /**
     * Adds an arbitrary feature to the child's configuration.
     */
    public fun feature(feature: Feature) {
        features.add(feature)
    }

    internal fun buildBindings(): List<PropertyBinding> =
        bindingsBuilder.bindings
}