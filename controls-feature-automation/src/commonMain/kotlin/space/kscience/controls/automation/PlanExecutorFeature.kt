package space.kscience.controls.automation

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import space.kscience.controls.api.features.Feature
import space.kscience.controls.common.meta.serializableToMeta
import space.kscience.dataforge.meta.Meta

/**
 * A feature indicating that the device can execute a [TransactionPlan].
 *
 * @property maxHistorySize The number of executed plans to keep in the history log.
 * @property allowParallelExecution If true, the device can execute multiple root plans simultaneously.
 */
@Serializable
@SerialName("feature.planExecutor")
public data class PlanExecutorFeature(
    val maxHistorySize: Int = 10,
    val allowParallelExecution: Boolean = false
) : Feature {
    override fun toMeta(): Meta = serializableToMeta(serializer(), this)
}