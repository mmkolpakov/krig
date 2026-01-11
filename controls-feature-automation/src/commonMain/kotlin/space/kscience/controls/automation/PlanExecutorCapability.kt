package space.kscience.controls.automation

import space.kscience.controls.api.context.ExecutionContext
import space.kscience.controls.api.context.SystemPrincipal
import space.kscience.controls.core.capabilities.CapabilityKey
import space.kscience.controls.core.capabilities.DeviceCapability
import space.kscience.dataforge.meta.Meta

/**
 * A capability that allows a device to execute complex [TransactionPlan]s.
 *
 * By attaching this capability, a device gains the ability to orchestrate multi-step
 * operations, handle sub-transactions, and execute compensating actions (Saga pattern)
 * in case of failures.
 */
public interface PlanExecutorCapability : DeviceCapability {

    /**
     * Executes the given transaction plan on the attached device.
     *
     * @param plan The [TransactionPlan] to execute.
     * @param context The [ExecutionContext] for this operation, providing security and tracing.
     * @return An optional [Meta] object representing the result of the entire plan execution.
     * @throws space.kscience.controls.core.faults.DeviceActionException if the plan execution fails.
     */
    public suspend fun executePlan(
        plan: TransactionPlan,
        context: ExecutionContext = ExecutionContext(SystemPrincipal),
    ): Meta?

    /**
     * Cancels any currently running plans.
     */
    public suspend fun cancelAll()

    /**
     * Returns the status of the current execution queue.
     * @return A list of active or queued plan IDs.
     */
    public suspend fun getActivePlans(): List<String>

    override val key: CapabilityKey<PlanExecutorCapability> get() = Key

    public companion object Key : CapabilityKey<PlanExecutorCapability> {
        override val id: String = "capability.automation.planExecutor"
    }
}