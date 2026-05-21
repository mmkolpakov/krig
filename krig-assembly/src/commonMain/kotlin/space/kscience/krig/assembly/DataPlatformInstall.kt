package space.kscience.krig.assembly

import kotlinx.serialization.json.JsonObject
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.toMeta
import space.kscience.dataforge.names.Name
import space.kscience.krig.api.factory.DeviceFactory
import space.kscience.krig.core.contracts.Device
import space.kscience.krig.core.contracts.DeviceBlueprint

/**
 * Result of [installDataPlatform]: the original configuration plus the resolved blueprints
 * and (optionally) materialised devices when the full variant was requested.
 */
public data class InstalledDataPlatform(
    public val config: DataPlatformConfiguration,
    public val blueprints: Map<Name, DeviceBlueprint<*>>,
    public val devices: Map<Name, Device> = emptyMap(),
)

/**
 * Validates [config], resolves every `blueprintId` against the installed [BlueprintPlugin]
 * and returns an [InstalledDataPlatform] record without materialising devices. Throws when
 * validation fails or a blueprint is missing.
 */
public fun Context.installDataPlatform(config: DataPlatformConfiguration): InstalledDataPlatform {
    val errors = config.validate()
    if (errors.isNotEmpty()) {
        error("DataPlatformConfiguration is invalid:\n  - ${errors.joinToString("\n  - ")}")
    }
    return InstalledDataPlatform(config = config, blueprints = resolveBlueprints(config))
}

/**
 * Like [installDataPlatform] but additionally materialises every [SourceSpec] through its
 * factory. Source blueprints that are also [DeviceFactory] get the typed
 * path via `Factory.build(context, meta)`; plain [DeviceBlueprint]s (pure DTO) have no
 * factory path and are skipped — use a blueprint-specific construction helper instead.
 */
public fun Context.installDataPlatformAndMaterialise(
    config: DataPlatformConfiguration,
): InstalledDataPlatform {
    val base = installDataPlatform(config)
    val devices = mutableMapOf<Name, Device>()
    config.sources.forEach { spec ->
        val bp = base.blueprints[spec.id] ?: return@forEach
        val factory = bp as? DeviceFactory<*, *> ?: return@forEach
        val meta = spec.config.toMetaOrEmpty()
        devices[spec.id] = factory.build(this, meta)
    }
    return base.copy(devices = devices)
}

/** Decodes a [JsonObject] into [Meta] via kotlinx-serialization, falling back to empty on null. */
private fun JsonObject?.toMetaOrEmpty(): Meta =
    this?.toMeta() ?: Meta.EMPTY
