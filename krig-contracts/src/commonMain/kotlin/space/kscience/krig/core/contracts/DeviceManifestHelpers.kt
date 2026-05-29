package space.kscience.krig.core.contracts

import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.parseAsName
import space.kscience.krig.api.descriptors.ActionDescriptor
import space.kscience.krig.api.descriptors.PropertyDescriptor
import space.kscience.krig.api.features.PipelineFeatureSpec
import space.kscience.krig.core.meta.DeviceContractBuilder
import space.kscience.krig.core.meta.descriptorMap

/**
 * Builds a portable manifest from a pure [DeviceContractBuilder].
 *
 * The manifest is an export/catalog document: descriptors, features, version and
 * metadata. Execution is supplied separately by a [DeviceBackend].
 */
public fun manifestOf(
    id: Name,
    contract: DeviceContractBuilder,
    version: String = "0.1.0",
    features: Map<Name, PipelineFeatureSpec> = emptyMap(),
    meta: Meta = Meta.EMPTY,
    deviceContractFqName: String = "space.kscience.krig.core.contracts.Device",
): DeviceManifest = manifestOf(
    id = id,
    properties = contract.propertyContracts.descriptorMap(),
    actions = contract.actionContracts.descriptorMap(),
    version = version,
    features = features,
    meta = meta,
    deviceContractFqName = deviceContractFqName,
)

/** String-id overload of [manifestOf]. */
public fun manifestOf(
    id: String,
    contract: DeviceContractBuilder,
    version: String = "0.1.0",
    features: Map<Name, PipelineFeatureSpec> = emptyMap(),
    meta: Meta = Meta.EMPTY,
    deviceContractFqName: String = "space.kscience.krig.core.contracts.Device",
): DeviceManifest = manifestOf(
    id = id.parseAsName(),
    contract = contract,
    version = version,
    features = features,
    meta = meta,
    deviceContractFqName = deviceContractFqName,
)

public fun manifestOf(
    id: Name,
    properties: Map<Name, PropertyDescriptor>,
    actions: Map<Name, ActionDescriptor> = emptyMap(),
    version: String = "0.1.0",
    features: Map<Name, PipelineFeatureSpec> = emptyMap(),
    meta: Meta = Meta.EMPTY,
    deviceContractFqName: String = "space.kscience.krig.core.contracts.Device",
): DeviceManifest = SimpleDeviceManifest(
    id = id,
    version = version,
    features = features,
    properties = properties,
    actions = actions,
    meta = meta,
    deviceContractFqName = deviceContractFqName,
)

private data class SimpleDeviceManifest(
    override val id: Name,
    override val version: String,
    override val features: Map<Name, PipelineFeatureSpec>,
    override val properties: Map<Name, PropertyDescriptor>,
    override val actions: Map<Name, ActionDescriptor>,
    override val meta: Meta,
    override val deviceContractFqName: String,
) : DeviceManifest
