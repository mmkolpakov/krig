package space.kscience.controls.services

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import space.kscience.controls.core.addressing.Address
import space.kscience.controls.core.context.ExecutionContext
import space.kscience.controls.core.faults.SerializableDeviceFailure
import space.kscience.controls.core.identifiers.BlueprintId
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.context.Plugin
import space.kscience.dataforge.context.PluginFactory
import space.kscience.dataforge.context.PluginTag
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.Name

/**
 * A serializable, self-contained specification for a computational task to be executed on a compute grid.
 * This object is sent by a client (e.g., a DeviceHub) to the ComputeGridService.
 *
 * @param I The input type of the task.
 * @param O The output type of the task.
 * @property taskBlueprintId The unique identifier of the `dataforge-data` TaskBlueprint to be executed.
 * @property input The serializable input for the task.
 * @property context Additional execution context, including security principal and correlation IDs.
 */
@Serializable
public data class TaskSpec<I, O>(
    val taskBlueprintId: BlueprintId,
    val input: I,
    val context: ExecutionContext,
)

/**
 * Represents the outcome of a task execution on the compute grid.
 * This is a sealed interface to old different stages of a potentially long-running task.
 *
 * @param O The output type of the task.
 */
@Serializable
public sealed interface TaskResult<out O> {
    /**
     * The task completed successfully.
     * @property output The result of the task execution.
     */
    @Serializable
    public data class Success<O>(val output: O) : TaskResult<O>

    /**
     * The task failed during execution.
     * @property failure A serializable representation of the error.
     */
    @Serializable
    public data class Failure(val failure: SerializableDeviceFailure) : TaskResult<Nothing>

    /**
     * An intermediate progress update for a long-running task.
     * @property progress A value between 0.0 and 1.0 representing completion progress.
     * @property message An optional message describing the current stage.
     */
    @Serializable
    public data class Progress(val progress: Float, val message: String? = null) : TaskResult<Nothing>
}


/**
 * A contract for a service that orchestrates the execution of computationally-intensive tasks
 * on a distributed "compute grid" of workers. The hub acts as a client to this service,
 * offloading tasks instead of executing them locally.
 */
public interface ComputeGridService : Plugin {
    override val tag: PluginTag get() = Companion.tag

    /**
     * Submits a task to the compute grid for execution.
     *
     * @param spec The full specification of the task to be executed.
     * @return A [Flow] that emits [TaskResult] updates. The flow will typically emit zero or more
     *         [TaskResult.Progress] messages, and will complete with either a single [TaskResult.Success]
     *         or a single [TaskResult.Failure] message.
     */
    public fun <I, O> submitTask(spec: TaskSpec<I, O>): Flow<TaskResult<O>>

    public companion object : PluginFactory<ComputeGridService> {
        override val tag: PluginTag = PluginTag("device.compute.grid", group = PluginTag.DATAFORGE_GROUP)

        /**
         * The default factory throws an error, as a concrete implementation must be provided by a runtime module.
         */
        override fun build(context: Context, meta: Meta): ComputeGridService {
            error("ComputeGridService is a service interface and requires a runtime-specific implementation.")
        }
    }
}

/**
 * A contract for a message broker used by compute workers to claim tasks and report results.
 * This separates the client-facing API ([ComputeGridService]) from the worker-facing API.
 */
public interface TaskBroker {
    /**
     * A worker calls this method to claim a task that matches its capabilities.
     * This is a suspendable method that may block until a suitable task is available.
     *
     * @param capabilities A [Meta] object describing the worker's capabilities (e.g., available memory, CPU, specific libraries).
     * @return A [TaskSpec] for the claimed task, or `null` if the request times out or is cancelled.
     */
    public suspend fun claimTask(capabilities: Meta): TaskSpec<*, *>?

    /**
     * A worker calls this method to report the progress or final result of a task.
     *
     * @param result The [TaskResult] to report.
     */
    public suspend fun reportResult(result: TaskResult<*>)

    /**
     * A worker calls this method to resolve a data dependency required for a task.
     * The service will route this request to the appropriate hub to read a device property.
     *
     * @param address The network-wide address of the device.
     * @param property The name of the property to read.
     * @return The [Meta] value of the property.
     */
    public suspend fun resolveDependency(address: Address, property: Name): Meta
}