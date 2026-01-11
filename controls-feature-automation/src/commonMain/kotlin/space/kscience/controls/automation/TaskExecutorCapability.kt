package space.kscience.controls.automation

import space.kscience.controls.api.context.ExecutionContext
import space.kscience.controls.api.context.SystemPrincipal
import space.kscience.controls.core.capabilities.CapabilityKey
import space.kscience.controls.core.capabilities.DeviceCapability
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.Name

/**
 * A capability allowing a device to execute `dataforge-data` tasks.
 * This capability acts as a bridge between the Device Control Plane and the DataForge Task execution engine.
 */
public interface TaskExecutorCapability : DeviceCapability {

    /**
     * Executes a `dataforge-data` task by its name.
     *
     * @param taskName The name of the task to execute (resolved from the blueprint).
     * @param input An optional [Meta] object containing input parameters for the task.
     * @param context The execution context for this operation.
     * @return An optional [Meta] object representing the result of the task.
     */
    public suspend fun executeTask(
        taskName: Name,
        input: Meta? = null,
        context: ExecutionContext = ExecutionContext(SystemPrincipal),
    ): Meta?

    override val key: CapabilityKey<TaskExecutorCapability> get() = Key

    public companion object Key : CapabilityKey<TaskExecutorCapability> {
        override val id: String = "capability.taskExecutor"
    }
}