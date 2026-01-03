package space.kscience.controls.automation

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import space.kscience.controls.api.descriptors.ActionLogicSource

/**
 * Specifies that the action's logic is defined inline as a declarative [TransactionPlan].
 */
@Serializable
@SerialName("logic.plan")
public data class PlanLogic(val plan: TransactionPlan) : ActionLogicSource