package space.kscience.krig.core.meta

import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.parseAsName
import space.kscience.krig.api.features.PipelineFeatureSpec
import space.kscience.krig.core.contracts.DeviceManifest
import space.kscience.krig.core.contracts.manifestOf
import space.kscience.krig.core.contracts.schemaHash

/**
 * Generated, typed view of a known device contract.
 *
 * A [DeviceManifest] is the dynamic/distributed document. This registry keeps the local typed
 * contracts next to that manifest, so endpoints that share the same contract id/version/hash can
 * activate typed facades without removing the dynamic Meta path.
 */
public data class DeviceContractRegistry(
    public val id: Name,
    public val version: String,
    public val deviceContractFqName: String,
    public val propertiesByName: Map<Name, DevicePropertyContract<*>>,
    public val actionsByName: Map<Name, DeviceActionContract<*, *>>,
    public val manifest: DeviceManifest,
    public val schemaHash: String,
)

/**
 * Builds a [DeviceContractRegistry] from a pure [DeviceContractBuilder].
 *
 * KSP-generated contract artifacts call this helper; dynamic devices may keep using [manifestOf]
 * directly when no typed registry exists.
 */
public fun deviceContractRegistry(
    id: Name,
    contract: DeviceContractBuilder,
    version: String = "0.1.0",
    features: Map<Name, PipelineFeatureSpec> = emptyMap(),
    meta: Meta = Meta.EMPTY,
    deviceContractFqName: String = "space.kscience.krig.core.contracts.Device",
): DeviceContractRegistry {
    val propertiesByName = contract.propertyContracts.associateBy { it.name }
    val actionsByName = contract.actionContracts.associateBy { it.name }
    val manifest = manifestOf(
        id = id,
        properties = propertiesByName.mapValues { it.value.descriptor },
        actions = actionsByName.mapValues { it.value.descriptor },
        version = version,
        features = features,
        meta = meta,
        deviceContractFqName = deviceContractFqName,
    )
    return DeviceContractRegistry(
        id = id,
        version = version,
        deviceContractFqName = deviceContractFqName,
        propertiesByName = propertiesByName,
        actionsByName = actionsByName,
        manifest = manifest,
        schemaHash = manifest.schemaHash(),
    )
}

/** String-id overload of [deviceContractRegistry]. */
public fun deviceContractRegistry(
    id: String,
    contract: DeviceContractBuilder,
    version: String = "0.1.0",
    features: Map<Name, PipelineFeatureSpec> = emptyMap(),
    meta: Meta = Meta.EMPTY,
    deviceContractFqName: String = "space.kscience.krig.core.contracts.Device",
): DeviceContractRegistry = deviceContractRegistry(
    id = id.parseAsName(),
    contract = contract,
    version = version,
    features = features,
    meta = meta,
    deviceContractFqName = deviceContractFqName,
)
