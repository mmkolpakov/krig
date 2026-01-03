package space.kscience.controls.composite.dsl

import space.kscience.controls.composite.dsl.children.ChildConfigBuilder
import space.kscience.controls.composite.dsl.children.MirrorBuilder
import space.kscience.controls.composite.dsl.guards.GuardsBuilder
import space.kscience.controls.core.runtime.HydratableDeviceState
import space.kscience.controls.core.InternalControlsApi
import space.kscience.controls.core.contracts.Device
import space.kscience.controls.core.contracts.DeviceBlueprint
import space.kscience.controls.api.features.Feature
import space.kscience.controls.core.meta.DeviceActionSpec
import space.kscience.controls.core.meta.DevicePropertySpec
import space.kscience.controls.core.meta.DeviceStreamSpec
import space.kscience.dataforge.names.Name

/**
 * Defines the visibility scope for members declared within a [DeviceSpecification].
 * This mechanism mirrors Kotlin's visibility modifiers, allowing for proper encapsulation
 * and the creation of clean public APIs for device blueprints.
 */
public enum class Visibility {
    /** The member is part of the public, published API of the device. */
    PUBLIC,

    /** The member is visible only within the `DeviceSpecification` where it is declared and its subclasses. */
    PROTECTED,

    /** The member is visible within the same module, but not to external consumers. */
    INTERNAL,

    /** The member is visible only within the `DeviceSpecification` where it is declared. */
    PRIVATE
}


/**
 * A base class for creating reusable, type-safe specifications for a device blueprint.
 * This class provides an object-oriented approach to defining the structure and behavior of a device.
 *
 * ### Key Concepts
 *
 * 1.  **Reusability:** By defining a device's properties, actions, and configuration in a class,
 *     you can easily reuse this specification across different parts of your application or in different projects.
 *
 * 2.  **Automatic Registration:** Properties, actions, and streams are declared as members using delegated properties
 *     (e.g., `val myProp by doubleProperty { ... }`). These delegates automatically register their
 *     corresponding specifications (`DevicePropertySpec`, `DeviceActionSpec`, etc.) with this `DeviceSpecification` instance
 *     at the moment of its creation.
 *
 * 3.  **Structured Configuration:** The abstract `configure()` method provides a dedicated scope
 *     ([CompositeSpecBuilder]) for defining non-delegated parts of the blueprint, such as the `driver`,
 *     `children`, and `lifecycle`. This separates concerns and makes the specification easier to read.
 *
 * 4.  **Visibility Control**: Use the `private { ... }`, `internal { ... }`, and `protected { ... }` scope functions
 *     to control the visibility of declared members, creating a clean public API for your blueprints.
 *
 */
public abstract class DeviceSpecification<D : Device> {
    /**
     * The unique identifier for the blueprint created from this specification.
     * If null, the simple name of the class will be used as the ID.
     * It is highly recommended to provide a unique, reverse-DNS style ID.
     */
    public open val id: String? = null

    /**
     * A version string for the blueprint created from this specification, preferably using semantic versioning.
     * This allows runtimes to handle different versions, enabling features like state migration.
     */
    public open val version: String = "0.1.0"

    // --- Visibility Management ---
    @PublishedApi
    internal var currentVisibility: Visibility = Visibility.PUBLIC

    /**
     * Defines a scope for members that are private to this `DeviceSpecification`.
     * Private members are part of the internal implementation and are not exposed in the final blueprint's public API.
     * They are fully accessible to the device's driver logic.
     */
    @CompositeSpecDsl
    protected fun private(block: DeviceSpecification<D>.() -> Unit) {
        val old = currentVisibility
        currentVisibility = Visibility.PRIVATE
        this.apply(block)
        currentVisibility = old
    }

    /**
     * Defines a scope for members that are internal.
     * In the context of blueprints, this visibility is treated similarly to `protected` but signals an
     * intent for module-wide use rather than inheritance.
     */
    @CompositeSpecDsl
    protected fun internal(block: DeviceSpecification<D>.() -> Unit) {
        val old = currentVisibility
        currentVisibility = Visibility.INTERNAL
        this.apply(block)
        currentVisibility = old
    }

    /**
     * Defines a scope for members that are protected.
     * Protected members are not part of the public API but are intended to be used by subclasses of this specification.
     */
    @CompositeSpecDsl
    protected fun protected(block: DeviceSpecification<D>.() -> Unit) {
        val old = currentVisibility
        currentVisibility = Visibility.PROTECTED
        this.apply(block)
        currentVisibility = old
    }

    // --- Member Registration ---

    /**
     * An internal container to group member specifications by their type (property, action, stream)
     * for a single visibility level.
     */
    @PublishedApi
    internal class MemberRegistry<D : Device> {
        val properties = mutableListOf<DevicePropertySpec<D, *>>()
        val actions = mutableListOf<DeviceActionSpec<D, *, *>>()
        val streams = mutableListOf<DeviceStreamSpec<D>>()
    }

    /**
     * A map that stores member registries keyed by their [Visibility].
     * This structure replaces multiple individual lists, centralizing member storage.
     */
    @PublishedApi
    internal val membersByVisibility: Map<Visibility, MemberRegistry<D>> = mapOf(
        Visibility.PUBLIC to MemberRegistry<D>(),
        Visibility.PROTECTED to MemberRegistry<D>(),
        Visibility.INTERNAL to MemberRegistry<D>(),
        Visibility.PRIVATE to MemberRegistry<D>()
    )

    @PublishedApi
    internal val requiredFeatures: MutableMap<String, Feature> = mutableMapOf()

    @PublishedApi
    @OptIn(InternalControlsApi::class)
    internal val registeredHydrators: MutableMap<Name, HydratableDeviceState<D, *>> = mutableMapOf()

    @PublishedApi
    internal fun <T : DevicePropertySpec<D, *>> registerPropertySpec(spec: T): T {
        membersByVisibility.getValue(currentVisibility).properties.add(spec)
        return spec
    }

    @PublishedApi
    internal fun <T : DeviceActionSpec<D, *, *>> registerActionSpec(spec: T): T {
        membersByVisibility.getValue(currentVisibility).actions.add(spec)
        return spec
    }

    @PublishedApi
    internal fun registerStreamSpec(spec: DeviceStreamSpec<D>): DeviceStreamSpec<D> {
        membersByVisibility.getValue(currentVisibility).streams.add(spec)
        return spec
    }

    @PublishedApi
    internal fun registerFeature(feature: Feature) {
        requiredFeatures.getOrPut(feature.capability) { feature }
    }

    @PublishedApi
    internal val includedFragments: MutableList<SpecificationFragment<D>> = mutableListOf()

    /**
     * Includes a reusable fragment of a device specification.
     * This method can be called from subclasses to apply common configurations.
     */
    @CompositeSpecDsl
    internal fun include(fragment: SpecificationFragment<D>) {
        includedFragments.add(fragment)
    }

    /**
     * Configures the device blueprint using the provided DSL builder [CompositeSpecBuilder].
     * This is where all non-delegated components (driver, children, lifecycle, metadata) should be defined.
     * All delegated properties, actions, and their required features are automatically registered.
     */
    protected abstract fun CompositeSpecBuilder<D>.configure()

    /**
     * Internal method to apply the complete configuration to a builder instance.
     * It registers all delegated properties, actions, streams, and required features, then calls the user-defined [configure] block.
     * This method respects member visibility, registering members with the appropriate methods on the builder.
     */
    @OptIn(InternalControlsApi::class)
    @PublishedApi
    internal fun apply(builder: CompositeSpecBuilder<D>) {
        // Apply all included fragments first. This allows the main `configure` block to override them.
        includedFragments.forEach { it.apply(builder) }

        // Register members based on their visibility.
        membersByVisibility.forEach { (visibility, registry) ->
            if (visibility == Visibility.PUBLIC) {
                registry.properties.forEach(builder::registerProperty)
                registry.actions.forEach(builder::registerAction)
                registry.streams.forEach(builder::registerStream)
            } else {
                // All non-public members are treated as "protected" from the builder's perspective.
                registry.properties.forEach(builder::registerProtectedProperty)
                registry.actions.forEach(builder::registerProtectedAction)
                registry.streams.forEach(builder::registerProtectedStream)
            }
        }

        // Transfer features and hydrators
        requiredFeatures.values.forEach { builder.feature(it) }
        builder.registeredHydrators.putAll(registeredHydrators)

        // Call the user-defined configuration block
        builder.configure()
    }
}

/**
 * Declares a single child component directly within a `DeviceSpecification`.
 * This is a DSL convenience function that creates and includes a [SpecificationFragment]
 * to defer the configuration to the build process.
 *
 * @param D The parent device contract type.
 * @param C The child device contract type.
 * @param name The local name for the child component.
 * @param blueprint The blueprint for the child device.
 * @param configBuilder A DSL block to configure the child's lifecycle, meta, and property bindings.
 */
@CompositeSpecDsl
public fun <D : Device, C : Device> DeviceSpecification<D>.child(
    name: Name,
    blueprint: DeviceBlueprint<C>,
    configBuilder: ChildConfigBuilder<D, C>.() -> Unit = {},
) {
    include(specificationFragment {
        child(name, blueprint, configBuilder)
    })
}

/**
 * Defines a set of operational guards directly within a `DeviceSpecification`.
 * This is a DSL convenience function that creates and includes a [SpecificationFragment].
 *
 * @param block A lambda with a [GuardsBuilder] receiver where guards are defined.
 */
@CompositeSpecDsl
public fun <D : Device> DeviceSpecification<D>.guards(block: GuardsBuilder.() -> Unit) {
    include(specificationFragment {
        guards(block)
    })
}

/**
 * Defines a set of remote property mirrors directly within a `DeviceSpecification`.
 * This is a DSL convenience function that creates and includes a [SpecificationFragment].
 *
 * @param block A lambda with a [MirrorBuilder] receiver where mirrors are defined.
 */
@CompositeSpecDsl
public fun <D : Device> DeviceSpecification<D>.mirrors(block: MirrorBuilder<D>.() -> Unit) {
    include(specificationFragment {
        mirrors(block)
    })
}