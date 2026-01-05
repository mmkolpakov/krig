package space.kscience.controls.core.contracts

import space.kscience.controls.api.context.ExecutionContext
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.context.Plugin
import space.kscience.dataforge.context.PluginFactory
import space.kscience.dataforge.context.PluginTag
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.misc.Named
import space.kscience.dataforge.names.Name

/**
 * A contract for a reusable, standalone piece of business logic that can be executed as a device action.
 * This interface embodies the "Strategy" design pattern, allowing the logic of an action to be decoupled
 * from its declaration in a [DeviceBlueprint].
 *
 * Implementations of this interface can be versioned, independently tested, and reused across multiple
 * device blueprints, promoting modularity and reducing code duplication.
 *
 * The logic is [Named], allowing it to be organized hierarchically and discovered using DataForge's
 * standard provider mechanisms.
 *
 * @param D The type of the device on which this logic can operate. This is contravariant (`in`) to allow
 *          logic defined for a general device contract (e.g., `Device`) to be used on a more specific one.
 * @param I The input type for the action logic.
 * @param O The output type of the action logic.
 */
public interface DeviceActionLogic<in D : Device, in I, out O> : Named {
    /**
     * The version of this logic implementation, preferably using semantic versioning.
     * This is crucial for the runtime to select a compatible implementation based on the
     * constraints defined in an [ActionDescriptor].
     */
    public val version: String

    /**
     * Declares the dependencies required by this action *before* its execution.
     * The runtime will call this method first, resolve the dependencies (e.g., by reading properties),
     * and then pass them to the [execute] method. This allows for optimizations like parallel data fetching.
     *
     * The declared dependencies can be dynamic and depend on the [input] arguments of the action.
     *
     * @param input The input that will be passed to the `execute` method.
     * @return A list of [Name]s of properties that need to be read before execution.
     */
    public fun dependencies(input: I): List<Name>

    /**
     * Executes the action's business logic.
     *
     * @param device The device instance on which to execute the logic.
     * @param input The input argument for the action.
     * @param dependencies A map of pre-fetched dependency values, where keys correspond to the names
     *                     declared by the [dependencies] method. This ensures that all required data
     *                     is available synchronously within the execution block.
     * @param context The [ExecutionContext] providing security and tracing information.
     * @return The result of the execution, or `null` if the action produces no result.
     */
    public suspend fun execute(device: D, input: I, dependencies: Map<Name, Meta>, context: ExecutionContext): O?
}
