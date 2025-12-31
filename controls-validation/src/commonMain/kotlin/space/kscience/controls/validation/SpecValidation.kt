package space.kscience.controls.validation

import space.kscience.controls.core.identifiers.BlueprintId
import space.kscience.controls.core.contracts.DeviceBlueprint
import space.kscience.controls.automation.PlanExecutorDevice
import space.kscience.controls.automation.TaskExecutorDevice
import space.kscience.controls.core.descriptors.PropertyKind
import space.kscience.controls.core.state.StatefulDevice
import space.kscience.dataforge.meta.get
import space.kscience.dataforge.names.Name

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

// These private functions remain here as they are part of the core validation logic.
private fun validateCapabilities(blueprint: DeviceBlueprint<*>): List<ValidationError> {
    val errors = mutableListOf<ValidationError>()
    if (blueprint.actions.values.any { it.descriptor.meta["plan"] != null }) {
        if (blueprint.features.values.none { it.capability == PlanExecutorDevice.CAPABILITY }) {
            errors.add(ValidationError.InconsistentCapability(blueprint.id.value, PlanExecutorDevice.CAPABILITY))
        }
    }
    if (blueprint.actions.values.any { it.descriptor.taskBlueprintId != null }) {
        if (blueprint.features.values.none { it.capability == TaskExecutorDevice.CAPABILITY }) {
            errors.add(ValidationError.InconsistentCapability(blueprint.id.value, TaskExecutorDevice.CAPABILITY))
        }
    }
    if (blueprint.properties.values.any { it.descriptor.persistent || it.descriptor.kind == PropertyKind.LOGICAL }) {
        if (blueprint.features.values.none { it.capability == StatefulDevice.CAPABILITY }) {
            errors.add(ValidationError.InconsistentCapability(blueprint.id.value, StatefulDevice.CAPABILITY))
        }
    }
    return errors
}