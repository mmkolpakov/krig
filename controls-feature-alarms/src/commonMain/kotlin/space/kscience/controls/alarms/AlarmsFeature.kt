package space.kscience.controls.alarms

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import space.kscience.controls.common.meta.serializableToMeta
import space.kscience.controls.api.features.Feature
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.Name

/**
 * A feature that defines the alarm subsystem for a device.
 * It acts as a container for [AlarmDescriptor]s and global alarm policies.
 *
 * @property alarms The definitions of alarms supported by this device, keyed by their local name.
 */
@Serializable
@SerialName("feature.alarms")
public data class AlarmsFeature(
    val alarms: Map<Name, AlarmDescriptor> = emptyMap()
) : Feature {
    override fun toMeta(): Meta = serializableToMeta(serializer(), this)
}