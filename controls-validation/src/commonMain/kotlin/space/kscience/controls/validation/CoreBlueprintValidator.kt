package space.kscience.controls.validation

import space.kscience.controls.api.descriptors.attribute
import space.kscience.controls.api.descriptors.attributes.FsmAttribute
import space.kscience.controls.core.legacy_alpha_2.contracts.DeviceBlueprint
import space.kscience.controls.api.descriptors.PropertyKind

/**
 * Performs core validation checks on a blueprint that are not tied to a specific feature.
 * This includes checking action preconditions.
 *
 * @param blueprint The blueprint to validate.
 * @return A list of validation errors.
 */
internal fun validateCoreBlueprint(blueprint: DeviceBlueprint<*>): List<ValidationError> {
    val errors = mutableListOf<ValidationError>()
    // Validate action requirements
    blueprint.actions.values.forEach { action ->
        val actionFsm = action.descriptor.attribute<FsmAttribute>()
        actionFsm?.requiredPredicates?.forEach { predicateName ->
            val predicateSpec = blueprint.properties[predicateName]
            if (predicateSpec == null) {
                errors.add(
                    ValidationError.InvalidActionRequirement(
                        blueprint.id.value,
                        action.name,
                        predicateName,
                        "Required predicate property not found on the blueprint."
                    )
                )
            } else if (predicateSpec.descriptor.kind != PropertyKind.PREDICATE) {
                errors.add(
                    ValidationError.InvalidActionRequirement(
                        blueprint.id.value,
                        action.name,
                        predicateName,
                        "Property is not of kind PREDICATE."
                    )
                )
            }
        }
    }
    return errors
}