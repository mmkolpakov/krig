package space.kscience.controls.alarms

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import space.kscience.controls.core.features.Feature
import space.kscience.controls.core.features.FeatureKey
import space.kscience.controls.core.serialization.serializableToMeta
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.Name

/**
 * A feature that defines the alarm subsystem for a device.
 * It acts as a container for [AlarmDescriptor]s and global alarm policies.
 *
 * @property alarms The definitions of alarms supported by this device, keyed by their local name.
 */
@Serializable
@SerialName(AlarmsFeature.ID)
public data class AlarmsFeature(
    val alarms: Map<Name, AlarmDescriptor> = emptyMap()
) : Feature {
    override val key: FeatureKey<*> get() = AlarmsFeature
    override val capability: String get() = CAPABILITY

    override fun toMeta(): Meta = serializableToMeta(serializer(), this)

    public companion object : FeatureKey<AlarmsFeature> {
        public const val ID: String = "feature.alarms"
        public const val CAPABILITY: String = "space.kscience.controls.alarms.AlarmSource"

        override val id: String = ID
    }
}