package space.kscience.controls.composite.dsl.properties

import space.kscience.controls.composite.dsl.CompositeSpecDsl
import space.kscience.controls.core.faults.DeviceFaultException
import space.kscience.controls.core.legacy_alpha_2.contracts.Device
import space.kscience.controls.api.faults.ValidationFault
import space.kscience.controls.api.validation.MinLengthRuleDescriptor
import space.kscience.controls.api.validation.RangeRuleDescriptor
import space.kscience.controls.api.validation.RegexRuleDescriptor
import space.kscience.controls.api.validation.ValidationRuleDescriptor
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.Value

/**
 * A rule container used internally by [ValidationBuilder].
 */
private class RuntimeValidationRule<in D : Device, in T>(
    val predicate: suspend D.(T) -> Boolean,
    val messageBuilder: (T) -> String,
)

/**
 * A type-safe DSL builder for defining validation rules for a mutable property.
 * This builder is accessed via the `validation { ... }` block within a property delegate.
 *
 * The builder collects two types of rules:
 * 1.  **Serializable Rules:** Declarative constraints like `range` or `pattern` that are stored
 *     as [ValidationRuleSpec] in the `DeviceBlueprint`. These can be analyzed by static tools and UIs.
 * 2.  **Runtime Rules:** Complex, non-serializable logic defined via `require { ... }`. These are
 *     executed by the runtime before a property write.
 *
 * @param D The type of the device contract.
 * @param T The type of the property value being validated.
 */
@CompositeSpecDsl
public class ValidationBuilder<D : Device, T> {
    /**
     * Internal storage for serializable, declarative validation rules.
     */
    public val descriptors: MutableList<ValidationRuleDescriptor> = mutableListOf()

    /**
     * Private storage for non-serializable, runtime-only validation logic.
     */
    private val runtimePredicates = mutableListOf<RuntimeValidationRule<D, T>>()

    /**
     * Adds a declarative rule to constrain a comparable value to a minimum and/or maximum bound.
     * This rule is serializable and part of the static blueprint.
     *
     * @param T The type of the value, which must be [Comparable].
     * @param min The inclusive minimum allowed value. If null, there is no lower bound.
     * @param max The inclusive maximum allowed value. If null, there is no upper bound.
     */
    public fun <T : Comparable<T>> range(min: T? = null, max: T? = null) {
        descriptors.add(RangeRuleDescriptor(Value.of(min), Value.of(max)))
    }

    /**
     * Adds a declarative rule to ensure a string value matches a given regular expression.
     * This rule is serializable.
     *
     * @param regex The regular expression pattern that the string must match.
     */
    public fun pattern(regex: String) {
        descriptors.add(RegexRuleDescriptor(regex))
    }

    /**
     * Adds a declarative rule to ensure a string or collection has a minimum length.
     * This rule is serializable.
     *
     * @param length The minimum required length.
     */
    public fun minLength(length: Int) {
        descriptors.add(MinLengthRuleDescriptor(length))
    }

    /**
     * Declares a non-serializable, runtime-only validation rule. If the `predicate` returns `false`,
     * the property write operation will fail, throwing a [DeviceFaultException] with a [ValidationFault].
     *
     * The `predicate` lambda receives the device instance (`this`) as its context, allowing access
     * to other properties and methods for context-aware validation.
     *
     * ### Example
     * ```kotlin
     * validation {
     *     // Rule depending on another device property
     *     require({ this.read(isReady) }) { "Device must be in 'ready' state to change this property." }
     * }
     * ```
     *
     * @param predicate A suspendable lambda that returns `true` if the new value is valid, `false` otherwise.
     *                  It receives the new value as `it` and the device instance as `this`.
     * @param message A lambda that builds a human-readable error message if the validation fails.
     *                It receives the invalid value as `it`.
     */
    public fun require(predicate: suspend D.(T) -> Boolean, message: (T) -> String) {
        runtimePredicates.add(RuntimeValidationRule(predicate, message))
    }

    /**
     * Builds a single, composite runtime validation lambda from all registered `require` rules.
     * @return A suspendable lambda that takes a device and a value, and throws a [DeviceFaultException] on failure.
     */
    public fun build(): (suspend D.(T) -> Unit)? = if(runtimePredicates.isEmpty()) null else {
        { value ->
            runtimePredicates.forEach { rule ->
                if (!rule.predicate(this, value)) {
                    throw DeviceFaultException(
                        ValidationFault(
                            details = Meta { "reason" put rule.messageBuilder(value) }
                        )
                    )
                }
            }
        }
    }
}