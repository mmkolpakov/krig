package space.kscience.controls.automation

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import space.kscience.controls.api.features.Feature
import space.kscience.controls.api.features.FeatureKey
import space.kscience.controls.common.meta.serializableToMeta
import space.kscience.dataforge.meta.Meta

/**
 * A feature indicating that the device can execute a [TransactionPlan].
 *
 * @property maxHistorySize The number of executed plans to keep in the history log for introspection.
 * @property allowParallelExecution If true, the device can execute multiple root plans simultaneously.
 *                                  If false, new plans will queue or fail while one is running.
 */
@Serializable
@SerialName(PlanExecutorFeature.ID)
public data class PlanExecutorFeature(
    val maxHistorySize: Int = 10,
    val allowParallelExecution: Boolean = false
) : Feature {
    override val key: FeatureKey<*> get() = PlanExecutorFeature
    override val capability: String get() = PlanExecutorDevice.CAPABILITY

    override fun toMeta(): Meta = serializableToMeta(serializer(), this)

    public companion object : FeatureKey<PlanExecutorFeature> {
        public const val ID: String = "feature.planExecutor"
        override val id: String = ID
    }
}