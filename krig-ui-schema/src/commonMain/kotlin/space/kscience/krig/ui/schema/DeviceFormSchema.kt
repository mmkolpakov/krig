package space.kscience.krig.ui.schema

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import space.kscience.dataforge.meta.descriptors.toJsonSchema
import space.kscience.dataforge.names.Name
import space.kscience.krig.api.descriptors.ActionDescriptor
import space.kscience.krig.api.descriptors.PropertyDescriptor
import space.kscience.krig.api.descriptors.PropertyKind
import space.kscience.krig.api.descriptors.TypeId
import space.kscience.krig.api.descriptors.attributes.description
import space.kscience.krig.api.descriptors.attributes.mutable
import space.kscience.krig.api.descriptors.attributes.readable
import space.kscience.krig.api.descriptors.attributes.unit
import space.kscience.krig.core.contracts.DeviceManifest
import space.kscience.krig.core.contracts.schemaHash
import space.kscience.krig.core.contracts.toJsonSchema

/**
 * Neutral form model projected from a device manifest.
 *
 * It describes controls and diagnostics a renderer may choose to display; it does not prescribe a
 * concrete layout toolkit. Dynamic properties discovered at runtime are kept separate from manifest
 * properties so clients can expose them without pretending that they are part of the stable contract.
 */
@Serializable
@SerialName("schema.device.form")
public data class DeviceFormSchema(
    public val manifestId: Name,
    public val manifestVersion: String,
    public val deviceContractFqName: String,
    public val schemaHash: String,
    public val properties: List<DeviceFormProperty>,
    public val actions: List<DeviceFormAction>,
    public val discoveredProperties: List<DeviceFormProperty> = emptyList(),
)

@Serializable
public enum class DeviceFormPropertyOrigin {
    Manifest,
    Discovered,
}

@Serializable
@SerialName("schema.device.form.property")
public data class DeviceFormProperty(
    public val name: Name,
    public val kind: PropertyKind,
    public val valueTypeId: TypeId,
    public val readable: Boolean,
    public val mutable: Boolean,
    public val description: String? = null,
    public val unit: String? = null,
    public val valueSchema: JsonObject,
    public val origin: DeviceFormPropertyOrigin = DeviceFormPropertyOrigin.Manifest,
)

@Serializable
@SerialName("schema.device.form.action")
public data class DeviceFormAction(
    public val name: Name,
    public val description: String? = null,
    public val inputSchema: JsonObject,
    public val outputSchema: JsonObject,
)

public fun DeviceManifest.toDeviceFormSchema(
    discoveredProperties: Iterable<PropertyDescriptor> = emptyList(),
): DeviceFormSchema = DeviceFormSchema(
    manifestId = id,
    manifestVersion = version,
    deviceContractFqName = deviceContractFqName,
    schemaHash = schemaHash(),
    properties = properties.values
        .sortedBy { it.name.toString() }
        .map { it.toDeviceFormProperty(DeviceFormPropertyOrigin.Manifest) },
    actions = actions.values
        .sortedBy { it.name.toString() }
        .map { it.toDeviceFormAction() },
    discoveredProperties = discoveredProperties
        .sortedBy { it.name.toString() }
        .map { it.toDeviceFormProperty(DeviceFormPropertyOrigin.Discovered) },
)

public fun PropertyDescriptor.toDeviceFormProperty(
    origin: DeviceFormPropertyOrigin = DeviceFormPropertyOrigin.Manifest,
): DeviceFormProperty = DeviceFormProperty(
    name = name,
    kind = kind,
    valueTypeId = valueTypeId,
    readable = readable,
    mutable = mutable,
    description = description,
    unit = unit,
    valueSchema = toJsonSchema(),
    origin = origin,
)

public fun ActionDescriptor.toDeviceFormAction(): DeviceFormAction = DeviceFormAction(
    name = name,
    description = description,
    inputSchema = inputMetaDescriptor.toJsonSchema(),
    outputSchema = outputMetaDescriptor.toJsonSchema(),
)
