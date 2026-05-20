package space.kscience.krig.core.contracts

import space.kscience.krig.api.descriptors.ActionDescriptor
import space.kscience.krig.api.descriptors.PropertyDescriptor
import space.kscience.krig.api.features.FeatureSpec
import space.kscience.krig.core.meta.DeviceContractBuilder
import space.kscience.krig.core.meta.DeviceSpecBuilder
import space.kscience.krig.core.meta.descriptorMap
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.parseAsName

/**
 * Builds a portable [DeviceBlueprint] from a typed [DeviceSpecBuilder].
 *
 * The helper keeps blueprint DTOs explicit while removing the repetitive
 * descriptor-map plumbing from driver samples and production assembly code.
 */
public fun <D : Device> blueprintOf(
    id: Name,
    spec: DeviceSpecBuilder<D>,
    version: String = "0.1.0",
    features: Map<Name, FeatureSpec> = emptyMap(),
    meta: Meta = Meta.EMPTY,
    deviceContractFqName: String = "space.kscience.krig.core.contracts.Device",
): DeviceBlueprint<D> = blueprintOf(
    id = id,
    properties = spec.propertySpecs.descriptorMap(),
    actions = spec.actionSpecs.descriptorMap(),
    version = version,
    features = features,
    meta = meta,
    deviceContractFqName = deviceContractFqName,
)

/** String-id overload of [blueprintOf]. */
public fun <D : Device> blueprintOf(
    id: String,
    spec: DeviceSpecBuilder<D>,
    version: String = "0.1.0",
    features: Map<Name, FeatureSpec> = emptyMap(),
    meta: Meta = Meta.EMPTY,
    deviceContractFqName: String = "space.kscience.krig.core.contracts.Device",
): DeviceBlueprint<D> = blueprintOf(
    id = id.parseAsName(),
    spec = spec,
    version = version,
    features = features,
    meta = meta,
    deviceContractFqName = deviceContractFqName,
)

/**
 * Builds a portable [DeviceBlueprint] from a pure [DeviceContractBuilder].
 *
 * This is the preferred industrial path: contracts stay free of backend read/write
 * lambdas, while typed backends provide execution.
 */
public fun <D : Device> blueprintOf(
    id: Name,
    contract: DeviceContractBuilder,
    version: String = "0.1.0",
    features: Map<Name, FeatureSpec> = emptyMap(),
    meta: Meta = Meta.EMPTY,
    deviceContractFqName: String = "space.kscience.krig.core.contracts.Device",
): DeviceBlueprint<D> = blueprintOf(
    id = id,
    properties = contract.propertyContracts.descriptorMap(),
    actions = contract.actionContracts.descriptorMap(),
    version = version,
    features = features,
    meta = meta,
    deviceContractFqName = deviceContractFqName,
)

/** String-id overload of [blueprintOf] for pure contracts. */
public fun <D : Device> blueprintOf(
    id: String,
    contract: DeviceContractBuilder,
    version: String = "0.1.0",
    features: Map<Name, FeatureSpec> = emptyMap(),
    meta: Meta = Meta.EMPTY,
    deviceContractFqName: String = "space.kscience.krig.core.contracts.Device",
): DeviceBlueprint<D> = blueprintOf(
    id = id.parseAsName(),
    contract = contract,
    version = version,
    features = features,
    meta = meta,
    deviceContractFqName = deviceContractFqName,
)

private fun <D : Device> blueprintOf(
    id: Name,
    properties: Map<Name, PropertyDescriptor>,
    actions: Map<Name, ActionDescriptor>,
    version: String,
    features: Map<Name, FeatureSpec>,
    meta: Meta,
    deviceContractFqName: String,
): DeviceBlueprint<D> = SimpleDeviceBlueprint(
    id = id,
    version = version,
    features = features,
    properties = properties,
    actions = actions,
    meta = meta,
    deviceContractFqName = deviceContractFqName,
)

private data class SimpleDeviceBlueprint<D : Device>(
    override val id: Name,
    override val version: String,
    override val features: Map<Name, FeatureSpec>,
    override val properties: Map<Name, PropertyDescriptor>,
    override val actions: Map<Name, ActionDescriptor>,
    override val meta: Meta,
    override val deviceContractFqName: String,
) : DeviceBlueprint<D> {
    override fun toMeta(): Meta = meta
}
