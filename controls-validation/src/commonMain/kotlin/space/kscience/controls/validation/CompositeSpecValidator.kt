package space.kscience.controls.validation

import space.kscience.controls.api.descriptors.attribute
import space.kscience.controls.api.descriptors.attributes.AccessAttribute
import space.kscience.controls.connectivity.ChildBindingsFeature
import space.kscience.controls.connectivity.ConstPropertyBinding
import space.kscience.controls.connectivity.ParentPropertyBinding
import space.kscience.controls.connectivity.TransformedPropertyBinding
import space.kscience.controls.connectivity.composition
import space.kscience.controls.connectivity.connectivity
import space.kscience.controls.api.composition.LocalChildComponentConfig
import space.kscience.controls.api.composition.RemoteChildComponentConfig
import space.kscience.controls.api.identifiers.BlueprintId
import space.kscience.controls.core.legacy_alpha_2.contracts.DeviceBlueprint
import space.kscience.controls.services.discovery.BlueprintRegistry
import space.kscience.controls.services.discovery.blueprintRegistry
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.meta.descriptors.validate
import space.kscience.dataforge.names.Name

/**
 * A utility object for validating [DeviceBlueprint] instances.
 */
public object CompositeSpecValidator {
    /**
     * Performs a "deep", recursive validation of a [DeviceBlueprint].
     *
     * @param blueprint The blueprint to validate.
     * @param context The [Context] containing necessary services like [BlueprintRegistry] and [FeatureValidatorRegistry].
     * @return A list of [ValidationError]. An empty list signifies a valid blueprint.
     */
    public fun validateWithContext(blueprint: DeviceBlueprint<*>, context: Context): List<ValidationError> {
        return validateRecursively(blueprint, context.blueprintRegistry, context, emptySet())
    }

    private fun validateRecursively(
        blueprint: DeviceBlueprint<*>,
        registry: BlueprintRegistry,
        context: Context,
        validationPath: Set<BlueprintId>,
    ): List<ValidationError> {
        // 1. Cycle detection
        if (blueprint.id in validationPath) {
            return listOf(ValidationError.CyclicalDependency((validationPath + blueprint.id).toList()))
        }
        val newPath = validationPath + blueprint.id

        // 2. Self-validation (structural and core logic)
        val errors = blueprint.validateSelf().toMutableList()
        errors.addAll(validateCoreBlueprint(blueprint))

        // 3. Feature-specific validation using pluggable validators
        val validatorRegistry = context.featureValidatorRegistry
        blueprint.features.values.forEach { feature ->
            validatorRegistry.getValidatorFor(feature)?.let { validator ->
                errors.addAll(validator.validate(blueprint, feature, registry))
            }
        }

        val children = blueprint.composition?.children ?: emptyMap()

        // 4. Child configurations and recursive validation
        children.forEach { (childName, childConfig) ->
            val childBlueprint = registry.findById(childConfig.blueprintId)
            if (childBlueprint == null) {
                errors.add(ValidationError.BlueprintNotFound(childName, childConfig.blueprintId))
            } else {
                when (childConfig) {
                    is LocalChildComponentConfig -> validateLocalChild(blueprint, childName, childConfig, childBlueprint, errors)
                    is RemoteChildComponentConfig -> validateRemoteChild(blueprint, childName, childConfig, childBlueprint, errors)
                }
                // Recurse
                errors.addAll(validateRecursively(childBlueprint, registry, context, newPath))
            }
        }

        return errors
    }
}

private fun validateLocalChild(
    parentBlueprint: DeviceBlueprint<*>,
    childName: Name,
    childConfig: LocalChildComponentConfig,
    childBlueprint: DeviceBlueprint<*>,
    errors: MutableList<ValidationError>,
) {
    // Extract bindings from the ChildBindingsFeature
    val bindingsFeature = childConfig.features
        .find { it is ChildBindingsFeature } as? ChildBindingsFeature

    bindingsFeature?.bindings?.forEach { binding ->
        when (binding) {
            is ParentPropertyBinding -> {
                val sourceSpec = parentBlueprint.properties[binding.sourceName]
                val targetSpec = childBlueprint.properties[binding.targetName]

                if (sourceSpec == null) {
                    errors.add(ValidationError.InvalidBinding(childName, binding.targetName, "Source property '${binding.sourceName}' does not exist in the parent."))
                }
                if (targetSpec == null) {
                    errors.add(ValidationError.InvalidBinding(childName, binding.targetName, "Target property '${binding.targetName}' does not exist in child."))
                } else {
                    val targetAccess = targetSpec.descriptor.attribute<AccessAttribute>()
                    // Check mutability
                    if (targetAccess?.mutable != true) {
                        errors.add(ValidationError.InvalidBinding(childName, binding.targetName, "Target property '${binding.targetName}' is not mutable."))
                    }
                    // Simple type matching by name
                    if (sourceSpec != null && sourceSpec.descriptor.valueTypeName != targetSpec.descriptor.valueTypeName) {
                        errors.add(ValidationError.InvalidBinding(childName, binding.targetName, "Type mismatch: Parent is '${sourceSpec.descriptor.valueTypeName}', child expects '${targetSpec.descriptor.valueTypeName}'."))
                    }
                }
            }
            is ConstPropertyBinding -> {
                val targetSpec = childBlueprint.properties[binding.targetName]
                if (targetSpec == null) {
                    errors.add(ValidationError.InvalidBinding(childName, binding.targetName, "Target property '${binding.targetName}' not found in child."))
                } else {
                    val targetAccess = targetSpec.descriptor.attribute<AccessAttribute>()
                    if (targetAccess?.mutable != true) {
                        errors.add(
                            ValidationError.InvalidBinding(
                                childName,
                                binding.targetName,
                                "Target property '${binding.targetName}' is not mutable."
                            )
                        )
                    } else if (!targetSpec.descriptor.metaDescriptor.validate(binding.value.value)) {
                        errors.add(
                            ValidationError.InvalidBinding(
                                childName,
                                binding.targetName,
                                "Constant value '${binding.value}' is not valid for target property constraints."
                            )
                        )
                    }
                }
            }
            is TransformedPropertyBinding -> {
                if (parentBlueprint.properties[binding.sourceName] == null) {
                    errors.add(ValidationError.InvalidBinding(childName, binding.targetName, "Source property '${binding.sourceName}' for transformed binding does not exist in parent."))
                }
                if (childBlueprint.properties[binding.targetName] == null) {
                    errors.add(ValidationError.InvalidBinding(childName, binding.targetName, "Target property '${binding.targetName}' for transformed binding does not exist in child."))
                }
            }
        }
    }
}

private fun validateRemoteChild(
    parentBlueprint: DeviceBlueprint<*>,
    childName: Name,
//    TODO Parameter "childBlueprint" is never used
    childConfig: RemoteChildComponentConfig,
    childBlueprint: DeviceBlueprint<*>,
    errors: MutableList<ValidationError>,
) {
    val peerConnections = parentBlueprint.connectivity?.peerConnections ?: emptyMap()
    if (!peerConnections.containsKey(childConfig.peerName)) {
        errors.add(ValidationError.InvalidRemoteChild(childName, "Peer connection with name '${childConfig.peerName}' is not defined in the parent blueprint."))
    }
}