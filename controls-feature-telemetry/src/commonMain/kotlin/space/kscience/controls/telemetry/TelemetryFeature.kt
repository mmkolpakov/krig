package space.kscience.controls.telemetry

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import space.kscience.controls.api.features.Feature
import space.kscience.controls.api.features.FeatureKey
import space.kscience.controls.common.meta.serializableToMeta
import space.kscience.dataforge.meta.Meta
import kotlin.time.Duration

/**
 * Configures the device's telemetry transmission policy (Push Model).
 *
 * Unlike [DataSourceFeature] which declares *capability* to be read,
 * this feature configures *how* the device pushes updates to the bus.
 *
 * @property defaultInterval The default reporting interval for properties that don't have specific settings.
 *                           If null, properties are reported only on change (event-driven).
 * @property batchSize The maximum number of updates to group into a single [TelemetryPacket] to reduce network overhead.
 *                     Defaults to 1 (real-time/no batching).
 * @property enabled Whether telemetry transmission is globally enabled for this device.
 */
@Serializable
@SerialName(TelemetryFeature.ID)
public data class TelemetryFeature(
    val defaultInterval: Duration? = null,
    val batchSize: Int = 1,
    val enabled: Boolean = true
) : Feature {
    override val key: FeatureKey<*> get() = TelemetryFeature
    override val capability: String get() = CAPABILITY

    override fun toMeta(): Meta = serializableToMeta(serializer(), this)

    public companion object : FeatureKey<TelemetryFeature> {
        public const val ID: String = "feature.telemetry"
        public const val CAPABILITY: String = "space.kscience.controls.telemetry.TelemetrySource"

        override val id: String = ID
    }
}