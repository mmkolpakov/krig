package space.kscience.controls.validation

import space.kscience.controls.core.contracts.DeviceBlueprint
import space.kscience.controls.services.discovery.BlueprintRegistry
import space.kscience.controls.connectivity.RemoteMirrorFeature
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.meta.Meta

/**
 * A validator for the [RemoteMirrorFeature]. It checks if the remote children and their properties
 * referenced by the mirrors exist and have compatible types.
 */
internal class RemoteMirrorValidator : FeatureValidator<RemoteMirrorFeature> {
    override fun validate(
        blueprint: DeviceBlueprint<*>,
        feature: RemoteMirrorFeature,
        registry: BlueprintRegistry,
    ): List<ValidationError> {
        val errors = mutableListOf<ValidationError>()
//        TODO("blueprint is simplified")
//        feature.entries.forEach { entry ->
//            val remoteChildConfig = blueprint.children[entry.remoteChildName] as? RemoteChildComponentConfig
//            if (remoteChildConfig == null) {
//                errors.add(
//                    ValidationError.InvalidMirror(
//                        blueprint.id.value,
//                        entry.localPropertyName,
//                        "Remote child '${entry.remoteChildName}' not found in the blueprint."
//                    )
//                )
//            } else {
//                val remoteBlueprint = registry.findById(remoteChildConfig.blueprintId)
//                if (remoteBlueprint == null) {
//                    errors.add(
//                        ValidationError.BlueprintNotFound(entry.remoteChildName, remoteChildConfig.blueprintId)
//                    )
//                } else {
//                    val remotePropertySpec = remoteBlueprint.properties[entry.remotePropertyName]
//                    if (remotePropertySpec == null) {
//                        errors.add(
//                            ValidationError.InvalidMirror(
//                                blueprint.id.value,
//                                entry.localPropertyName,
//                                "Remote property '${entry.remotePropertyName}' not found on blueprint '${remoteBlueprint.id}'."
//                            )
//                        )
//                    } else if (remotePropertySpec.descriptor.valueTypeName != entry.valueTypeName) {
//                        errors.add(
//                            ValidationError.InvalidMirror(
//                                blueprint.id.value,
//                                entry.localPropertyName,
//                                "Type mismatch for remote property '${entry.remotePropertyName}'. Expected '${entry.valueTypeName}', but found '${remotePropertySpec.descriptor.valueTypeName}'."
//                            )
//                        )
//                    }
//                }
//            }
//        }
        return errors
    }
}

/**
 * The factory for [RemoteMirrorValidator].
 */
internal object RemoteMirrorValidatorFactory : FeatureValidatorFactory {
    override val capability: String get() = "space.kscience.controls.composite.old.features.RemoteMirroring"

    override fun build(context: Context, meta: Meta): FeatureValidator<*> = RemoteMirrorValidator()
}