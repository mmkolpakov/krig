package space.kscience.controls.api.descriptors.attributes

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import space.kscience.controls.api.descriptors.MemberAttribute
import space.kscience.controls.api.validation.ValidationRuleDescriptor
import space.kscience.controls.common.meta.serializableToMeta
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.Value
import space.kscience.dataforge.names.Name

/**
 * Attributes governing data persistence and history.
 */
@Serializable
@SerialName("attr.persistence")
public data class PersistenceAttribute(
    val persistent: Boolean = false,
    val transient: Boolean = false,
) : MemberAttribute {
    override fun toMeta(): Meta = serializableToMeta(serializer(), this)
}

/**
 * Attributes defining data validation rules.
 */
@Serializable
@SerialName("attr.validation")
public data class ValidationAttribute(
    val rules: List<ValidationRuleDescriptor> = emptyList(),
    val allowedValues: List<Value>? = null,
    val rangeMin: Double? = null,
    val rangeMax: Double? = null
) : MemberAttribute {
    override fun toMeta(): Meta = serializableToMeta(serializer(), this)
}

/**
 * Attributes defining the implementation source of an Action.
 */
@Serializable
@SerialName("attr.implementation")
public data class ImplementationAttribute(
    val logicId: Name? = null,
    val logicVersionConstraint: String? = null,
    val taskBlueprintId: String? = null,
    val distributable: Boolean = false,
    val taskInputTypeName: String? = null,
    val taskOutputTypeName: String? = null,
    // Arbitrary execution metadata (e.g. serialized Plan)
    val executionMeta: Meta = Meta.EMPTY
) : MemberAttribute {
    override fun toMeta(): Meta = serializableToMeta(serializer(), this)
}

/**
 * Attributes integrating an Action with the Operational FSM and other logic requirements.
 */
@Serializable
@SerialName("attr.fsm")
public data class FsmAttribute(
    val operationalEventTypeName: String? = null,
    val operationalEventMeta: Meta? = null,
    val operationalSuccessEventTypeName: String? = null,
    val operationalSuccessEventMeta: Meta? = null,
    val operationalFailureEventTypeName: String? = null,
    val operationalFailureEventMeta: Meta? = null,
    val possibleFaults: Set<String> = emptySet(),
    val requiredPredicates: Set<Name> = emptySet()
) : MemberAttribute {
    override fun toMeta(): Meta = serializableToMeta(serializer(), this)
}