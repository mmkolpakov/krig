package space.kscience.controls.automation

import space.kscience.controls.api.context.ExecutionContext
import space.kscience.controls.api.context.SystemPrincipal
import space.kscience.controls.core.contracts.Device
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.Name

/**
 * A capability interface for devices that can execute `dataforge-data` tasks.
 * This contract formalizes the integration with the DataForge workspace and task execution engine.
 * A [space.kscience.controls.core.contracts.DeviceBlueprint] must declare the [TaskExecutorFeature]
 * for a device to implement this interface. The runtime is responsible for checking this capability.
 */
public interface TaskExecutorDevice : Device {
    /**
     * Companion object holding stable identifiers for the capability.
     */
    public companion object {
        /**
         * The unique, fully-qualified name for the [TaskExecutorDevice] capability.
         */
        public const val CAPABILITY: String = "space.kscience.controls.automation.TaskExecutorDevice"
    }

    /**
     * Executes a `dataforge-data` task by its name. The runtime implementation of this method
     * should locate the corresponding `TaskBlueprint` (typically via the `taskBlueprintId`
     * in an `ActionDescriptor`) and use the `dataforge-workspace` to execute it.
     *
     * @param taskName The name of the task to execute.
     * @param input An optional [Meta] object containing input parameters for the task.
     * @param context The execution context for this operation.
     * @return An optional [Meta] object representing the result of the task.
     * @throws space.kscience.controls.core.faults.DeviceActionException if the task is not supported or fails.
     */
    public suspend fun executeTask(
        taskName: Name,
        input: Meta? = null,
        context: ExecutionContext = ExecutionContext(SystemPrincipal),
    ): Meta?
}