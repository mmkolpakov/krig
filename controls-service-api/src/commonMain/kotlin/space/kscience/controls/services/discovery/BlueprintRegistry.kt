package space.kscience.controls.services.discovery

import space.kscience.controls.core.identifiers.BlueprintId
import space.kscience.controls.core.contracts.DeviceBlueprint
import space.kscience.dataforge.context.*
import space.kscience.dataforge.meta.Meta

/**
 * A contract for a service that can discover and provide [DeviceBlueprint] instances by their unique IDs.
 * This is a critical component for building dynamic and distributed systems, as it allows a runtime
 * to instantiate devices whose logic is not known at compile time.
 *
 * In a distributed system, blueprints are expected to be available as pre-compiled artifacts (e.g., in JARs or modules)
 * on each node. This registry serves as a service locator to find them.
 */
public interface BlueprintRegistry : Plugin {
    override val tag: PluginTag get() = Companion.tag

    /**
     * Finds a [DeviceBlueprint] by its unique identifier.
     *
     * @param id The type-safe [BlueprintId] of the blueprint.
     * @return The corresponding [DeviceBlueprint], or `null` if no blueprint with that ID is found.
     */
    public fun findById(id: BlueprintId): DeviceBlueprint<*>?

    public companion object : PluginFactory<BlueprintRegistry> {
        override val tag: PluginTag = PluginTag("device.blueprint.registry", group = PluginTag.DATAFORGE_GROUP)

        /**
         * The default implementation of this factory throws an error, as a concrete implementation
         * must be provided by a runtime or a dedicated service discovery module.
         */
        override fun build(context: Context, meta: Meta): BlueprintRegistry {
            error("BlueprintRegistry is a service interface and requires a runtime-specific implementation.")
        }
    }
}

/**
 * A convenience extension to get the [BlueprintRegistry] from a context.
 * Throws an error if the plugin is not installed, as it's considered essential for dynamic systems.
 */
public val Context.blueprintRegistry: BlueprintRegistry
    get() = plugins.find(true) { it is BlueprintRegistry } as? BlueprintRegistry
        ?: error("BlueprintRegistry plugin is not installed in the context.")