package space.kscience.controls.automation

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import space.kscience.controls.core.features.Feature
import space.kscience.controls.core.features.FeatureKey
import space.kscience.controls.core.serialization.serializableToMeta
import space.kscience.dataforge.meta.Meta

/**
 * A feature indicating that the device can execute `dataforge-data` Tasks.
 *
 * @property supportedTaskBlueprints A list of `dataforge-data` Task Blueprint IDs that this device can execute.
 */
@Serializable
@SerialName(TaskExecutorFeature.ID)
public data class TaskExecutorFeature(
    val supportedTaskBlueprints: List<String>
) : Feature {
    override val key: FeatureKey<*> get() = TaskExecutorFeature
    override val capability: String get() = TaskExecutorDevice.CAPABILITY

    override fun toMeta(): Meta = serializableToMeta(serializer(), this)

    public companion object : FeatureKey<TaskExecutorFeature> {
        public const val ID: String = "feature.taskExecutor"
        override val id: String = ID
    }
}