package space.kscience.controls.api.validation

import kotlinx.serialization.Polymorphic
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import space.kscience.controls.common.meta.serializableToMeta
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.MetaRepr
import space.kscience.dataforge.meta.Value

/**
 * A declarative descriptor for a validation rule applied to a device property.
 *
 * This interface acts as a pure Data Transfer Object (DTO).
 * It describes *constraints*, but does not contain the validation logic itself.
 *
 * The logic is implemented by a `ValidationVerifier` (or `ValidatorCapability`) in the Runtime,
 * which maps these descriptors to actual executable checks.
 *
 * This interface is [Polymorphic] to allow external plugins to define custom validation rules
 * (e.g., checking values against a database, hardware interlocks, or complex math) without
 * modifying the core API.
 */
@Polymorphic
public interface ValidationRuleDescriptor : MetaRepr {
    /**
     * Converts the rule configuration to DataForge Meta.
     * Used when the rule needs to be stored in `PropertyDescriptor.attributes`.
     */
    override fun toMeta(): Meta
}

/**
 * A standard rule asserting that a value must fall within a specific range.
 * Applies to Comparable types (Number, String, Instant).
 *
 * @property min The lower bound (inclusive). If null, the bound is open (-Infinity).
 * @property max The upper bound (inclusive). If null, the bound is open (+Infinity).
 */
@Serializable
@SerialName("validation.range")
public data class RangeRuleDescriptor(
    val min: Value? = null,
    val max: Value? = null,
) : ValidationRuleDescriptor {
    override fun toMeta(): Meta = serializableToMeta(serializer(), this)
}

/**
 * A standard rule asserting that a String value must match a regular expression.
 *
 * @property pattern The regex pattern string.
 */
@Serializable
@SerialName("validation.regex")
public data class RegexRuleDescriptor(
    val pattern: String
) : ValidationRuleDescriptor {
    override fun toMeta(): Meta = serializableToMeta(serializer(), this)
}

/**
 * A standard rule asserting that a String or Collection has a minimum length/size.
 *
 * @property length The minimum required length.
 */
@Serializable
@SerialName("validation.minLength")
public data class MinLengthRuleDescriptor(
    val length: Int
) : ValidationRuleDescriptor {
    override fun toMeta(): Meta = serializableToMeta(serializer(), this)
}

/**
 * A rule that delegates validation to a named, registered logic component (Predicate).
 * This is the bridge between Declarative Schemas and custom runtime logic.
 *
 * The Runtime Hub is expected to have a `PredicateRegistry` where [predicateId] is mapped
 * to a function `(Value, Meta) -> Boolean`.
 *
 * @property predicateId The unique ID of the validator logic (e.g., "validators.crc32").
 * @property args Additional arguments passed to the validator logic (context).
 */
@Serializable
@SerialName("validation.custom")
public data class CustomPredicateRuleDescriptor(
    val predicateId: String,
    val args: Meta = Meta.EMPTY
) : ValidationRuleDescriptor {
    override fun toMeta(): Meta = serializableToMeta(serializer(), this)
}