package space.kscience.controls.composite.dsl

import space.kscience.controls.core.legacy_alpha_2.contracts.Device

/**
 * A reusable fragment of a device specification. This `fun interface` allows defining a
 * collection of properties, actions, or other configurations that can be applied to multiple
 * [DeviceSpecification]s or [CompositeSpecBuilder]s, promoting code reuse and modularity.
 *
 * The type parameter `D` is contravariant (`in`). This ensures strict type safety in a flexible way:
 * a fragment defined for a general contract (e.g., `Device`) can be applied to a builder for a more
 * specific contract (e.g., `MySpecificDevice`), but not vice versa.
 */
public fun interface SpecificationFragment<in D : Device> {
    /**
     * Applies the fragment's configuration to the given [CompositeSpecBuilder].
     * @param builder The builder instance to which the fragment's logic should be applied.
     */
    public fun apply(builder: CompositeSpecBuilder<out D>)
}

/**
 * A DSL method within `CompositeSpecBuilder` to include a [SpecificationFragment].
 * This function calls the fragment's `apply` method, passing the current builder instance
 * as the context.
 */
public fun <D : Device> CompositeSpecBuilder<D>.include(fragment: SpecificationFragment<D>) {
    fragment.apply(this)
}

/**
 * Combines two fragments into a single one. When the new, composite fragment is applied,
 * the left operand is applied first, followed by the right operand. This order is important
 * for configurations that can be overwritten, such as `meta` blocks.
 */
public operator fun <D : Device> SpecificationFragment<D>.plus(
    other: SpecificationFragment<D>,
): SpecificationFragment<D> = SpecificationFragment { builder ->
    this@plus.apply(builder)
    other.apply(builder)
}

/**
 * A factory function to create a [SpecificationFragment] with a type-safe DSL receiver.
 * This function is the primary entry point for creating fragments.
 *
 * It helps the compiler's type inference, allowing to omit the generic parameter
 * in most cases.
 *
 * ### Usage
 *
 * **1. Common case (for any Device):**
 * The type parameter `<Device>` is inferred automatically.
 * ```kotlin
 * val motionFragment = specificationFragment {
 *     // 'this' is a CompositeSpecBuilder<Device>
 *     doubleProperty("position") { ... }
 * }
 * ```
 *
 * **2. Specific case (for a specific device contract):**
 * When assigning to a typed variable, the compiler correctly infers the specific type.
 * ```kotlin
 * val motorFragment: SpecificationFragment<MyMotorDevice> = specificationFragment {
 *     // 'this' is a CompositeSpecBuilder<MyMotorDevice>
 * }
 * ```
 *
 * @param D The device contract type. Inferred in most cases.
 * @param block The DSL block where the fragment is defined. The receiver (`this`) is `CompositeSpecBuilder<D>`.
 * @return A new [SpecificationFragment] instance.
 */
public fun <D : Device> specificationFragment(
    block: CompositeSpecBuilder<D>.() -> Unit
): SpecificationFragment<D> = SpecificationFragment { builder ->
    @Suppress("UNCHECKED_CAST")
    (builder as CompositeSpecBuilder<D>).block()
}