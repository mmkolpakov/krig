package space.kscience.controls.api.validation

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import space.kscience.controls.api.meta.serializableToMeta
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.MetaRepr
import space.kscience.dataforge.meta.Value

/**
 * A sealed interface for a serializable, declarative validation rule for a device property.
 */
//TODO remove sealed
@Serializable
public sealed interface ValidationRuleDescriptor : MetaRepr

/**
 * A validation rule that constrains a comparable value to a minimum and/or maximum bound.
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
 * A validation rule that ensures a string value matches a given regular expression.
 */
@Serializable
@SerialName("validation.regex")
public data class RegexRuleDescriptor(val pattern: String) : ValidationRuleDescriptor {
    override fun toMeta(): Meta = serializableToMeta(serializer(), this)
}

/**
 * A validation rule that ensures a string or collection has a minimum length.
 */
@Serializable
@SerialName("validation.minLength")
public data class MinLengthRuleDescriptor(val length: Int) : ValidationRuleDescriptor {
    override fun toMeta(): Meta = serializableToMeta(serializer(), this)
}

/**
 * A validation rule that delegates the validation logic to a named, reusable predicate.
 */
@Serializable
@SerialName("validation.custom")
public data class CustomPredicateRuleDescriptor(val predicateId: String, val meta: Meta = Meta.EMPTY) : ValidationRuleDescriptor {
    override fun toMeta(): Meta = serializableToMeta(serializer(), this)
}