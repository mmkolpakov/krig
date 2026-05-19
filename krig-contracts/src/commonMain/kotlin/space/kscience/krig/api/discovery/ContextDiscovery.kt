package space.kscience.krig.api.discovery

import space.kscience.dataforge.context.Context
import space.kscience.dataforge.context.Plugin
import space.kscience.dataforge.context.PluginFactory
import space.kscience.dataforge.context.gather as gatherContributions
import space.kscience.dataforge.names.Name

/** Typed `gather` — returns `Map<Name, T>` for the element type encoded on [target]. */
public fun <T : Any> Context.gather(target: ContributionTarget<T>): Map<Name, T> {
    @Suppress("UNCHECKED_CAST")
    return gatherContributions<Any>(target.id) as Map<Name, T>
}

/** Returns the plugin produced by [factory] if installed, else `null`. */
public inline fun <reified P : Plugin> Context.pluginOrNull(factory: PluginFactory<P>): P? =
    plugins[factory]

/** Returns the plugin produced by [factory]; throws if not installed. */
public inline fun <reified P : Plugin> Context.requirePlugin(factory: PluginFactory<P>): P =
    pluginOrNull(factory) ?: error(
        "Plugin '${factory.tag.name}' not installed on Context '$name'.",
    )
