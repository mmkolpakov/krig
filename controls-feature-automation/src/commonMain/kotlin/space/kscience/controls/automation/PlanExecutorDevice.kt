package space.kscience.controls.automation

import space.kscience.controls.api.context.ExecutionContext
import space.kscience.controls.api.context.SystemPrincipal
import space.kscience.controls.core.legacy_alpha_2.contracts.Device
import space.kscience.dataforge.meta.Meta

/**
 * A capability interface for devices that can execute a [TransactionPlan].
 * This contract formalizes the ability of a device to orchestrate complex, multi-step operations.
 *
 * A [DeviceBlueprint] must declare the [PlanExecutorFeature]
 * for a device to implement this interface. The runtime is responsible for checking this capability
 * and invoking [executePlan] when a plan-based action is triggered.
 */
public interface PlanExecutorDevice : Device {
    /**
     * Companion object holding stable identifiers for the capability.
     */
    public companion object {
        /**
         * The unique, fully-qualified name for the [PlanExecutorDevice] capability.
         */
        public const val CAPABILITY: String = "space.kscience.controls.automation.PlanExecutorDevice"
    }

    /**
     * Executes the given transaction plan. The runtime dispatches calls to plan-based actions
     * to this method.
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
}