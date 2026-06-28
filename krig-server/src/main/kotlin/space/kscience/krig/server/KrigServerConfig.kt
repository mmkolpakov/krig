package space.kscience.krig.server

import kotlinx.serialization.Serializable
import space.kscience.dataforge.names.Name
import space.kscience.krig.core.contracts.Device
import space.kscience.krig.core.contracts.DeviceManifest
import space.kscience.krig.core.contracts.DiscardPolicy
import space.kscience.krig.core.contracts.SubscribeOptions

/**
 * Read-only registry exposed by the Ktor adapter.
 *
 * Implementations can be backed by a static map, a device hub, a catalog, or an application-specific
 * inventory. Manifests are keyed by runtime device id so one manifest can describe many devices.
 */
public interface DeviceServerRegistry {
    public val devices: Map<Name, Device>

    public fun manifest(deviceId: Name): DeviceManifest?
}

/** Immutable registry for applications that already have materialized devices and manifests. */
public class StaticDeviceServerRegistry(
    override val devices: Map<Name, Device>,
    private val manifestsByDevice: Map<Name, DeviceManifest> = emptyMap(),
) : DeviceServerRegistry {
    override fun manifest(deviceId: Name): DeviceManifest? = manifestsByDevice[deviceId]
}

/**
 * Server-wide knobs that affect route layout and future streaming defaults.
 *
 * [defaultSubscribeOptions] is exposed in `/server` now and must be used by streaming routes when
 * websocket/SSE endpoints are added, so browser-facing subscriptions start bounded by default.
 */
public data class KrigServerSettings(
    public val basePath: String = "",
    public val defaultSubscribeOptions: SubscribeOptions = DefaultSubscribeOptions,
) {
    public companion object {
        public val DefaultSubscribeOptions: SubscribeOptions = SubscribeOptions(
            maxRateHz = 10.0,
            queueSize = 256,
            discardPolicy = DiscardPolicy.KeepLatest,
        )
    }
}

/** Wire DTO for [KrigServerSettings.defaultSubscribeOptions]. */
@Serializable
public data class SubscribeOptionsDto(
    public val maxRateHz: Double?,
    public val typeFilter: List<String>,
    public val queueSize: Int?,
    public val discardPolicy: String,
)

internal fun SubscribeOptions.toDto(): SubscribeOptionsDto = SubscribeOptionsDto(
    maxRateHz = maxRateHz,
    typeFilter = typeFilter.sorted(),
    queueSize = queueSize,
    discardPolicy = discardPolicy.name,
)
