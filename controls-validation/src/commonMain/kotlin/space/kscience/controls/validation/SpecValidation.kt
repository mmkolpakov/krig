package space.kscience.controls.validation

import space.kscience.controls.core.contracts.DeviceBlueprint

/**
 * An exception thrown by `compositeDeviceValidated` when one or more validation errors are found.
 * @property errors A list of all validation errors found.
 */
public class BlueprintValidationException(public val errors: List<ValidationError>) :
    IllegalArgumentException("Blueprint validation failed with ${errors.size} error(s):\n${errors.joinToString("\n") { "  - ${it.message}" }}")


/**
 * Performs a "shallow" validation of a [DeviceBlueprint], checking only for internal consistency.
 *
 * @return A list of [ValidationError].
 */
public fun DeviceBlueprint<*>.validateSelf(): List<ValidationError> {
    val errors = mutableListOf<ValidationError>()

    // Check for name collisions
    val allNames = properties.keys + actions.keys + streams.keys
//    TODO("blueprint is simplified")
//    + children.keys + peerConnections.keys
    if (allNames.size != allNames.toSet().size) {
        val duplicates = allNames.groupBy { it }.filter { it.value.size > 1 }.keys
        duplicates.forEach { errors.add(ValidationError.ConflictingName(it)) }
    }

    // Validate consistency of declared capabilities (Features)
    errors.addAll(validateCapabilities(this))

    return errors
}

private fun validateCapabilities(blueprint: DeviceBlueprint<*>): List<ValidationError> {
    TODO()
}