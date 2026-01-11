package space.kscience.controls.automation

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import space.kscience.controls.api.features.Feature
import space.kscience.controls.common.meta.serializableToMeta
import space.kscience.dataforge.meta.Meta

/**
 * A feature indicating that the device can execute `dataforge-data` Tasks.
 *
 * @property supportedTaskBlueprints A list of `dataforge-data` Task Blueprint IDs that this device can execute.
 */
@Serializable
@SerialName("feature.taskExecutor")
public data class TaskExecutorFeature(
    val supportedTaskBlueprints: List<String>
) : Feature {
    override fun toMeta(): Meta = serializableToMeta(serializer(), this)
}