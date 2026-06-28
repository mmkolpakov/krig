package space.kscience.krig.core.contracts

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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

/**
 * JSON Schema for one property's value-meta, built on DataForge [MetaDescriptor.toJsonSchema].
 * KRig adds no schema logic of its own; it exposes the descriptor already carried by
 * [PropertyDescriptor.metaDescriptor].
 */
public fun PropertyDescriptor.toJsonSchema(): JsonObject = metaDescriptor.toJsonSchema()

/**
 * JSON Schema for an action's input and output, built on DataForge [MetaDescriptor.toJsonSchema].
 * Populated automatically when the contract is declared via `serializableActionContract`, so
 * external tooling sees a strict schema for the action's argument and result.
 */
public fun ActionDescriptor.toJsonSchema(): JsonObject = buildJsonObject {
    put("title", JsonPrimitive(name.toString()))
    put("input", inputMetaDescriptor.toJsonSchema())
    put("output", outputMetaDescriptor.toJsonSchema())
}

/**
 * JSON Schema (`type: object`) for a whole [DeviceManifest]. Each property name maps to the
 * schema of its [PropertyDescriptor.metaDescriptor], and action schemas are exposed separately
 * under `actions` so tooling can render/invoke them without a live device.
 */
public fun DeviceManifest.toJsonSchema(): JsonObject = buildJsonObject {
    put($$"$schema", JsonPrimitive("https://json-schema.org/draft/2020-12/schema"))
    put("title", JsonPrimitive(id.toString()))
    put("type", JsonPrimitive("object"))
    put("version", JsonPrimitive(version))
    put("deviceContractFqName", JsonPrimitive(deviceContractFqName))
    putJsonObject("properties") {
        properties.entries.sortedBy { it.key.toString() }.forEach { (name, descriptor) ->
            put(name.toString(), descriptor.metaDescriptor.toJsonSchema())
        }
    }
    putJsonObject("actions") {
        actions.entries.sortedBy { it.key.toString() }.forEach { (name, descriptor) ->
            put(name.toString(), descriptor.toJsonSchema())
        }
    }
}

/** Validates [meta] against this property's [MetaDescriptor] constraints. */
public fun PropertyDescriptor.validateMeta(meta: Meta?): Boolean = metaDescriptor.validate(meta)

/**
 * Stable content hash of a manifest schema.
 *
 * This is intentionally SDK-local and algorithm-prefixed. It is for contract equality checks
 * between KRig endpoints, not for cryptographic trust.
 */
public fun DeviceManifest.schemaHash(): String = stableSchemaHash(toJsonSchema())

private fun stableSchemaHash(element: JsonElement): String {
    val canonical = element.canonicalString()
    var hash = 0xcbf29ce484222325uL
    for (char in canonical) {
        hash = hash xor char.code.toULong()
        hash *= 0x100000001b3uL
    }
    return "fnv1a64:" + hash.toString(16).padStart(16, '0')
}

private fun JsonElement.canonicalString(): String = when (this) {
    is JsonObject -> entries
        .sortedBy { it.key }
        .joinToString(prefix = "{", postfix = "}") { (key, value) ->
            JsonPrimitive(key).toString() + ":" + value.canonicalString()
        }
    is JsonArray -> joinToString(prefix = "[", postfix = "]") { it.canonicalString() }
    else -> toString()
}
