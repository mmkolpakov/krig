package space.kscience.controls.telemetry

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import space.kscience.controls.api.features.Feature
import space.kscience.controls.common.meta.serializableToMeta
import space.kscience.dataforge.meta.Meta
import kotlin.time.Duration

/**
 * Configures the device's telemetry transmission policy (Push Model).
 *
 * @property defaultInterval The default reporting interval for properties.
 * @property batchSize The maximum number of updates to group into a single packet.
 * @property enabled Whether telemetry transmission is globally enabled.
 */
@Serializable
@SerialName("feature.telemetry")
public data class TelemetryFeature(
    val defaultInterval: Duration? = null,
    val batchSize: Int = 1,
    val enabled: Boolean = true
) : Feature {
    override fun toMeta(): Meta = serializableToMeta(serializer(), this)
}