package space.kscience.krig.assembly

import space.kscience.dataforge.context.Context
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.Name
import space.kscience.krig.api.factory.DeviceFactory
import space.kscience.krig.core.contracts.Device
import space.kscience.krig.core.contracts.DeviceManifest

/**
 * Result of [installDataPlatform]: the original configuration plus the resolved manifests
 * and (optionally) materialised devices when the full variant was requested.
 */
public data class InstalledDataPlatform(
    public val config: DataPlatformConfiguration,
    public val manifests: Map<Name, DeviceManifest>,
    public val devices: Map<Name, Device> = emptyMap(),
)

/**
 * Validates [config], resolves every `manifestId` against the installed [DeviceCatalog]
 * and returns an [InstalledDataPlatform] record without materialising devices. Throws when
 * validation fails or a Manifest is missing.
 */
public fun Context.installDataPlatform(config: DataPlatformConfiguration): InstalledDataPlatform {
    val errors = config.validate()
    if (errors.isNotEmpty()) {
        error("DataPlatformConfiguration is invalid:\n  - ${errors.joinToString("\n  - ")}")
    }
    return InstalledDataPlatform(config = config, manifests = resolveManifests(config))
}

/**
 * Like [installDataPlatform] but additionally materialises every [SourceSpec] through its
 * factory. Source manifests that are also [DeviceFactory] get the typed
 * path via `Factory.build(context, meta)`; plain [DeviceManifest]s (pure DTO) have no
 * factory path and are skipped — use a Manifest-specific construction helper instead.
 */
public fun Context.installDataPlatformAndMaterialise(
    config: DataPlatformConfiguration,
): InstalledDataPlatform {
    val base = installDataPlatform(config)
    val devices = mutableMapOf<Name, Device>()
    config.sources.forEach { spec ->
        val manifest = base.manifests[spec.id] ?: return@forEach
        val factory = manifest as? DeviceFactory<*, *> ?: return@forEach
        val meta = spec.configMeta()
        devices[spec.id] = factory.build(this, meta)
    }
    return base.copy(devices = devices)
}

/** Opaque DataForge Meta config passed to [DeviceFactory.build]. */
public fun SourceSpec.configMeta(): Meta = config
