package space.kscience.controls.validation

import space.kscience.controls.api.descriptors.attribute
import space.kscience.controls.api.descriptors.attributes.ImplementationAttribute
import space.kscience.controls.api.descriptors.attributes.PersistenceAttribute
import space.kscience.controls.core.contracts.DeviceBlueprint
import space.kscience.controls.automation.PlanExecutorDevice
import space.kscience.controls.automation.TaskExecutorDevice
import space.kscience.controls.api.descriptors.PropertyKind
import space.kscience.controls.core.state.StatefulDevice
import space.kscience.dataforge.meta.get

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
    val errors = mutableListOf<ValidationError>()

    // 1. Check PlanExecutor Capability
    val usesPlans = blueprint.actions.values.any { actionSpec ->
        val attr = actionSpec.descriptor.attribute<ImplementationAttribute>()
//        TODO magic string
        attr?.executionMeta?.get("plan") != null
    }
    if (usesPlans) {
        if (blueprint.features.values.none { it.capability == PlanExecutorDevice.CAPABILITY }) {
            errors.add(ValidationError.InconsistentCapability(blueprint.id.value, PlanExecutorDevice.CAPABILITY))
        }
    }

    // 2. Check TaskExecutor Capability
    val usesTasks = blueprint.actions.values.any { actionSpec ->
        val attr = actionSpec.descriptor.attribute<ImplementationAttribute>()
        attr?.taskBlueprintId != null
    }
    if (usesTasks) {
        if (blueprint.features.values.none { it.capability == TaskExecutorDevice.CAPABILITY }) {
            errors.add(ValidationError.InconsistentCapability(blueprint.id.value, TaskExecutorDevice.CAPABILITY))
        }
    }

    // 3. Check Persistence Capability
    val needsPersistence = blueprint.properties.values.any { propSpec ->
        val persistenceAttr = propSpec.descriptor.attribute<PersistenceAttribute>()
        val isPersistent = persistenceAttr?.persistent == true
        // PropertyKind is a core field of the descriptor, not an attribute
        val isLogical = propSpec.descriptor.kind == PropertyKind.LOGICAL
        isPersistent || isLogical
    }
    if (needsPersistence) {
        if (blueprint.features.values.none { it.capability == StatefulDevice.CAPABILITY }) {
            errors.add(ValidationError.InconsistentCapability(blueprint.id.value, StatefulDevice.CAPABILITY))
        }
    }
    return errors
}