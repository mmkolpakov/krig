package space.kscience.krig.schema.json

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import me.kpavlov.kt.schema.generator.json.serialization.SerializationClassJsonSchemaGenerator
import me.kpavlov.kt.schema.json.AdditionalPropertiesConstraint
import me.kpavlov.kt.schema.json.JsonSchema
import me.kpavlov.kt.schema.json.JsonSchemaConstants
import me.kpavlov.kt.schema.json.PropertyDefinition
import space.kscience.dataforge.meta.descriptors.MetaDescriptor
import space.kscience.dataforge.meta.descriptors.toJsonSchema
import space.kscience.krig.api.descriptors.ActionDescriptor
import space.kscience.krig.api.descriptors.PropertyDescriptor
import space.kscience.krig.api.descriptors.attributes.description
import space.kscience.krig.core.contracts.DeviceManifest

/**
 * JSON settings used for KRig schema projection.
 *
 * The module exposes typed `kt-schema` objects and keeps raw JSON conversion at transport boundaries.
 */
public val KrigSchemaJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
}

/** Converts a typed JSON Schema value to raw JSON for HTTP/OpenAPI compatibility. */
public fun JsonSchema.toJsonObject(json: Json = KrigSchemaJson): JsonObject =
    json.encodeToJsonElement(JsonSchema.serializer(), this).jsonObject

/** Encodes a typed JSON Schema value to a stable JSON string. */
public fun JsonSchema.toSchemaString(json: Json = KrigSchemaJson): String =
    json.encodeToString(JsonSchema.serializer(), this)

/** Decodes raw JSON Schema produced at a boundary into the typed `kt-schema` model. */
public fun JsonObject.toKrigJsonSchema(json: Json = KrigSchemaJson): JsonSchema =
    json.decodeFromJsonElement(JsonSchema.serializer(), this)

/** Projects a DataForge [MetaDescriptor] to typed JSON Schema without changing DataForge validation semantics. */
public fun MetaDescriptor.toKrigJsonSchema(json: Json = KrigSchemaJson): JsonSchema =
    toJsonSchema().toKrigJsonSchema(json)

/** JSON Schema projection of one property value. */
public fun PropertyDescriptor.toKrigJsonSchema(json: Json = KrigSchemaJson): JsonSchema =
    metaDescriptor.toKrigJsonSchema(json).copy(
        title = name.toString(),
        description = description,
    )

/** Input/output JSON Schema projection for an action descriptor. */
@Serializable
public data class KrigActionJsonSchemas(
    public val input: JsonSchema,
    public val output: JsonSchema,
)

/** JSON Schema projection of one action's Meta input and output descriptors. */
public fun ActionDescriptor.toKrigJsonSchemas(json: Json = KrigSchemaJson): KrigActionJsonSchemas =
    KrigActionJsonSchemas(
        input = inputMetaDescriptor.toKrigJsonSchema(json).copy(title = "${name}.input"),
        output = outputMetaDescriptor.toKrigJsonSchema(json).copy(title = "${name}.output"),
    )

/** JSON Schema projection of a manifest's stable property contract. */
public fun DeviceManifest.toKrigJsonSchema(json: Json = KrigSchemaJson): JsonSchema = JsonSchema(
    schema = JsonSchemaConstants.JSON_SCHEMA_ID_DRAFT202012,
    title = id.toString(),
    description = deviceContractFqName,
    properties = properties.entries
        .sortedBy { it.key.toString() }
        .associate { (name, descriptor) -> name.toString() to descriptor.toKrigJsonSchema(json).asPropertyDefinition() },
    additionalProperties = AdditionalPropertiesConstraint.deny(),
)

/** Generates a JSON Schema for a serializable DTO through kt-schema's SerialDescriptor path. */
public inline fun <reified T> serializableKrigJsonSchema(): JsonSchema =
    SerializationClassJsonSchemaGenerator.jsonSchemaOf<T>()

private fun JsonSchema.asPropertyDefinition(): PropertyDefinition = this
