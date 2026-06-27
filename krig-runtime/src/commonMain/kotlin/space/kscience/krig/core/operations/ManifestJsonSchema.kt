package space.kscience.krig.core.operations

import space.kscience.dataforge.meta.descriptors.validate
import space.kscience.krig.core.contracts.DeviceManifest

/**
 * Built-in [ManifestValidationHook] checking that each property descriptor's own declared
 * defaults satisfy its [MetaDescriptor] constraints, via [MetaDescriptor.validate]. Surfaces authoring
 * mistakes — a default value contradicting the descriptor's declared value type, restriction or
 * allowed-values set — as a WARNING before materialization.
 */
public object MetaDescriptorDefaultsValidationHook : ManifestValidationHook {
    override fun validate(manifest: DeviceManifest): List<ManifestValidationMessage> =
        manifest.properties.values.mapNotNull { descriptor ->
            if (descriptor.metaDescriptor.validate(descriptor.metaDescriptor.defaultNode)) {
                null
            } else {
                ManifestValidationMessage(
                    severity = ManifestValidationMessage.Severity.WARNING,
                    message = "Property '${descriptor.name}' declares defaults that violate its own metaDescriptor.",
                    category = "metadescriptor.defaults",
                )
            }
        }
}
