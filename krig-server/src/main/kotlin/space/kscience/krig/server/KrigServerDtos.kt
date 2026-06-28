package space.kscience.krig.server

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import space.kscience.dataforge.meta.toJson
import space.kscience.dataforge.meta.descriptors.toJsonSchema
import space.kscience.krig.api.data.ObservedValue
import space.kscience.krig.api.descriptors.ActionDescriptor
import space.kscience.krig.api.descriptors.PropertyKind
import space.kscience.krig.api.descriptors.PropertyDescriptor
import space.kscience.krig.api.result.OperationOutcome
import space.kscience.krig.api.result.map
import space.kscience.krig.core.contracts.DeviceManifest
import space.kscience.krig.core.contracts.schemaHash
import space.kscience.krig.core.contracts.toJsonSchema

/** Server capabilities and conservative defaults visible to clients. */
@Serializable
public data class KrigServerInfoDto(
    public val apiVersion: String,
    public val readOnly: Boolean,
    public val defaultSubscribeOptions: SubscribeOptionsDto,
)

/** Device inventory response. */
@Serializable
public data class DeviceTreeDto(
    public val devices: List<DeviceSummaryDto>,
)

/** One runtime device entry in [DeviceTreeDto]. */
@Serializable
public data class DeviceSummaryDto(
    public val id: String,
    public val manifestId: String? = null,
    public val schemaHash: String? = null,
)

/** Portable manifest projection for HTTP/OpenAPI clients. */
@Serializable
public data class DeviceManifestDto(
    public val id: String,
    public val version: String,
    public val deviceContractFqName: String,
    public val schemaHash: String,
    public val featureIds: List<String>,
    public val properties: List<PropertyDescriptorDto>,
    public val actions: List<ActionDescriptorDto>,
    public val meta: JsonElement,
)

/** Property descriptor plus its value JSON Schema. */
@Serializable
public data class PropertyDescriptorDto(
    public val name: String,
    public val kind: String,
    public val valueTypeId: String,
    public val schema: JsonObject,
)

/** Action descriptor plus input/output JSON Schemas. */
@Serializable
public data class ActionDescriptorDto(
    public val name: String,
    public val inputSchema: JsonObject,
    public val outputSchema: JsonObject,
)

/** Read response that keeps predictable operation faults inside [OperationOutcome]. */
@Serializable
public data class PropertyReadDto(
    public val deviceId: String,
    public val property: String,
    public val outcome: OperationOutcome<JsonElement>,
)

/** Quality-aware read response for clients that should not lose timestamp/quality semantics. */
@Serializable
public data class ObservedReadDto(
    public val deviceId: String,
    public val property: String,
    public val outcome: OperationOutcome<ObservedMetaDto>,
)

/** JSON-friendly projection of `ObservedValue<Meta?>`. */
@Serializable
public data class ObservedMetaDto(
    public val value: JsonElement?,
    public val time: String,
    public val quality: DataQualityDto,
)

/** Flat quality projection with stable labels for dashboards and generated clients. */
@Serializable
public data class DataQualityDto(
    public val severity: Int,
    public val label: String,
    public val code: String? = null,
    public val detail: String? = null,
)

/** Routing/protocol fault outside a device operation. */
@Serializable
public data class ServerFaultDto(
    public val type: String,
    public val message: String,
)

internal fun DeviceManifest.toDto(): DeviceManifestDto = DeviceManifestDto(
    id = id.toString(),
    version = version,
    deviceContractFqName = deviceContractFqName,
    schemaHash = schemaHash(),
    featureIds = features.keys.map { it.toString() }.sorted(),
    properties = properties.values.sortedBy { it.name.toString() }.map { it.toDto() },
    actions = actions.values.sortedBy { it.name.toString() }.map { it.toDto() },
    meta = meta.toJson(),
)

internal fun PropertyDescriptor.toDto(): PropertyDescriptorDto = PropertyDescriptorDto(
    name = name.toString(),
    kind = kind.toWireId(),
    valueTypeId = valueTypeId.toString(),
    schema = toJsonSchema(),
)

internal fun ActionDescriptor.toDto(): ActionDescriptorDto = ActionDescriptorDto(
    name = name.toString(),
    inputSchema = inputMetaDescriptor.toJsonSchema(),
    outputSchema = outputMetaDescriptor.toJsonSchema(),
)

private fun PropertyKind.toWireId(): String = when (this) {
    PropertyKind.PHYSICAL -> "kind.physical"
    PropertyKind.LOGICAL -> "kind.logical"
    PropertyKind.SETPOINT -> "kind.setpoint"
    PropertyKind.MEASURED -> "kind.measured"
}

internal fun OperationOutcome<space.kscience.dataforge.meta.Meta>.toPropertyReadDto(
    deviceId: String,
    property: String,
): PropertyReadDto = PropertyReadDto(
    deviceId = deviceId,
    property = property,
    outcome = map { it.toJson() },
)

internal fun OperationOutcome<ObservedValue<space.kscience.dataforge.meta.Meta?>>.toObservedReadDto(
    deviceId: String,
    property: String,
): ObservedReadDto = ObservedReadDto(
    deviceId = deviceId,
    property = property,
    outcome = map { observed ->
        ObservedMetaDto(
            value = observed.value?.toJson(),
            time = observed.time.toString(),
            quality = DataQualityDto(
                severity = observed.quality.severity.rank,
                label = observed.quality.severity.label,
                code = observed.quality.code?.id,
                detail = observed.quality.detail,
            ),
        )
    },
)
