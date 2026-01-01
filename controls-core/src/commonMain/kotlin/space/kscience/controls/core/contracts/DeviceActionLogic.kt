package space.kscience.controls.core.contracts

import space.kscience.controls.core.context.ExecutionContext
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

/**
 * A service contract for a runtime plugin that can discover and provide instances of [DeviceActionLogic].
 * The runtime uses this service to resolve `logicId` references from an [ActionDescriptor] to a
 * concrete, executable implementation.
 */
public interface ActionLogicProvider : Plugin {
    override val tag: PluginTag get() = Companion.tag

    /**
     * Finds a [DeviceActionLogic] implementation by its unique hierarchical name and an optional version constraint.
     *
     * @param id The [Name] identifier of the logic.
     * @param version A version string or a version constraint. It is recommended to follow the
     *                Maven version range specification (e.g., "1.2.0", "[1.0, 2.0)", "1.3.+").
     *                The implementation of this service is responsible for parsing and resolving
     *                the constraint against the versions of available logic implementations.
     *                If null, the provider should return the latest available version.
     * @return The found [DeviceActionLogic] instance, or `null` if no compatible logic is found. The return type uses
     *         star projections because the provider cannot know the specific generic types at lookup time; the
     *         runtime is responsible for ensuring type safety before execution.
     */
    public fun findById(id: Name, version: String?): DeviceActionLogic<*, *, *>?

    public companion object : PluginFactory<ActionLogicProvider> {
        override val tag: PluginTag = PluginTag("device.action.logic.provider", group = PluginTag.DATAFORGE_GROUP)

        /**
         * The default factory throws an error, as a concrete implementation must be provided by a runtime module.
         */
        override fun build(context: Context, meta: Meta): ActionLogicProvider {
            error("ActionLogicProvider is a service interface and requires a runtime-specific implementation.")
        }
    }
}