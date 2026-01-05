package space.kscience.controls.core.contracts

import space.kscience.dataforge.context.Context
import space.kscience.dataforge.context.Plugin
import space.kscience.dataforge.context.PluginFactory
import space.kscience.dataforge.context.PluginTag
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.Name

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