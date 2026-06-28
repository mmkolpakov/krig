package space.kscience.krig.server

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Builds the small OpenAPI document served by `/openapi.json`. */
public fun krigOpenApiDocument(settings: KrigServerSettings = KrigServerSettings()): JsonObject {
    val prefix = settings.normalizedOpenApiPrefix()
    return buildJsonObject {
        put("openapi", "3.1.0")
        putJsonObject("info") {
            put("title", "KRig Device Server")
            put("version", "0.1.0")
        }
        putJsonObject("paths") {
            readOnlyGet("$prefix/server", "Server capabilities and defaults")
            readOnlyGet("$prefix/tree", "Device tree")
            readOnlyGet("$prefix/devices", "Device list")
            readOnlyGet("$prefix/devices/{deviceId}", "Device summary")
            readOnlyGet("$prefix/devices/{deviceId}/manifest", "Device manifest projection")
            readOnlyGet("$prefix/devices/{deviceId}/schema", "Device JSON Schema")
            readOnlyGet("$prefix/devices/{deviceId}/actions", "Device action descriptors")
            readOnlyGet("$prefix/devices/{deviceId}/properties/{property}", "Read a property as Meta JSON")
            readOnlyGet(
                "$prefix/devices/{deviceId}/observations/{property}",
                "Read a quality-aware observed property",
            )
        }
        putJsonObject("components") {
            putJsonObject("schemas") {
                jsonSchema("DeviceTreeDto")
                jsonSchema("DeviceManifestDto")
                jsonSchema("PropertyReadDto")
                jsonSchema("ObservedReadDto")
                jsonSchema("ServerFaultDto")
            }
        }
    }
}

private fun JsonObjectBuilderScope.readOnlyGet(path: String, summary: String) {
    putJsonObject(path) {
        putJsonObject("get") {
            put("summary", summary)
            putJsonObject("responses") {
                putJsonObject("200") {
                    put("description", "Successful response")
                }
                putJsonObject("404") {
                    put("description", "Device or manifest was not found")
                }
            }
        }
    }
}

private fun JsonObjectBuilderScope.jsonSchema(name: String) {
    putJsonObject(name) {
        put("type", "object")
        putJsonArray("x-krig-note") {
            add(JsonPrimitive("Schema is intentionally compact in the MVP; route-specific device schemas are served separately."))
        }
    }
}

private fun KrigServerSettings.normalizedOpenApiPrefix(): String {
    val trimmed = basePath.trim('/')
    return if (trimmed.isEmpty()) "" else "/$trimmed"
}

private typealias JsonObjectBuilderScope = kotlinx.serialization.json.JsonObjectBuilder
