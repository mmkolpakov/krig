package space.kscience.krig.core.contracts

import space.kscience.krig.api.features.DeviceFeatureSpec
import space.kscience.krig.api.identifiers.BlueprintId
import space.kscience.krig.api.identifiers.toBlueprintId
import space.kscience.krig.core.meta.DeviceSpecBuilder
import space.kscience.krig.core.meta.descriptorMap
import space.kscience.dataforge.meta.Meta

/**
 * Builds a portable [DeviceBlueprint] from a typed [DeviceSpecBuilder].
 *
 * The helper keeps blueprint DTOs explicit while removing the repetitive
 * descriptor-map plumbing from driver samples and production assembly code.
 */
public fun <D : Device> blueprintOf(
    id: BlueprintId,
    spec: DeviceSpecBuilder<D>,
    version: String = "0.1.0",
    features: Map<String, DeviceFeatureSpec> = emptyMap(),
    meta: Meta = Meta.EMPTY,
    deviceContractFqName: String = "space.kscience.krig.core.contracts.Device",
): DeviceBlueprint<D> = object : DeviceBlueprint<D> {
    override val id: BlueprintId = id
    override val version: String = version
    override val features: Map<String, DeviceFeatureSpec> = features
    override val properties = spec.propertySpecs.descriptorMap()
    override val actions = spec.actionSpecs.descriptorMap()
    override val meta: Meta = meta
    override val deviceContractFqName: String = deviceContractFqName
    override fun toMeta(): Meta = meta
}

/** String-id overload of [blueprintOf]. */
public fun <D : Device> blueprintOf(
    id: String,
    spec: DeviceSpecBuilder<D>,
    version: String = "0.1.0",
    features: Map<String, DeviceFeatureSpec> = emptyMap(),
    meta: Meta = Meta.EMPTY,
    deviceContractFqName: String = "space.kscience.krig.core.contracts.Device",
): DeviceBlueprint<D> = blueprintOf(
    id = id.toBlueprintId(),
    spec = spec,
    version = version,
    features = features,
    meta = meta,
    deviceContractFqName = deviceContractFqName,
)
