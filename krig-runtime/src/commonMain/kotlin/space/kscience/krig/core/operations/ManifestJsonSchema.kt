package space.kscience.krig.core.operations

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonObject
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.descriptors.MetaDescriptor
import space.kscience.dataforge.meta.descriptors.toJsonSchema
import space.kscience.dataforge.meta.descriptors.validate
import space.kscience.krig.api.descriptors.ActionDescriptor
import space.kscience.krig.api.descriptors.PropertyDescriptor
import space.kscience.krig.core.contracts.DeviceManifest

/**
 * JSON Schema for one property's value-meta, built on DataForge [MetaDescriptor.toJsonSchema].
 * krig adds no schema logic of its own — it exposes the descriptor already carried
 * by [PropertyDescriptor.metaDescriptor].
 */
public fun PropertyDescriptor.toJsonSchema(): JsonObject = metaDescriptor.toJsonSchema()

/**
 * JSON Schema for an action's input and output, built on DataForge [MetaDescriptor.toJsonSchema].
 * Populated automatically when the contract is declared via `serializableActionContract`, so external
 * tooling sees a strict schema for the action's argument and result.
 */
public fun ActionDescriptor.toJsonSchema(): JsonObject = buildJsonObject {
    put("title", JsonPrimitive(name.toString()))
    put("input", inputMetaDescriptor.toJsonSchema())
    put("output", outputDescriptor.toJsonSchema())
}

/**
 * JSON Schema (`type: object`) for a whole [DeviceManifest]: each property name maps to the schema of its
 * [PropertyDescriptor.metaDescriptor]. Suitable for external tooling, config editors and contract docs.
 */
public fun DeviceManifest.toJsonSchema(): JsonObject = buildJsonObject {
    put($$"$schema", JsonPrimitive("https://json-schema.org/draft/2020-12/schema"))
    put("title", JsonPrimitive(id.toString()))
    put("type", JsonPrimitive("object"))
    putJsonObject("properties") {
        properties.forEach { (name, descriptor) ->
            put(name.toString(), descriptor.metaDescriptor.toJsonSchema())
        }
    }
}

/** Validates [meta] against this property's [MetaDescriptor] (value type / restriction / allowed values). */
public fun PropertyDescriptor.validateMeta(meta: Meta?): Boolean = metaDescriptor.validate(meta)

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
