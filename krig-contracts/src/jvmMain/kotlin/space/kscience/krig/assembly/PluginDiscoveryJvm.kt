package space.kscience.krig.assembly

import space.kscience.dataforge.context.Context
import space.kscience.dataforge.context.PluginFactory
import space.kscience.dataforge.context.classLoaderPlugin

/**
 * Returns every [PluginFactory] registered through `META-INF/services` on this context's
 * classloader. Delegates to DataForge's [space.kscience.dataforge.context.ClassLoaderPlugin],
 * the standard scientific-plugin SPI shipped with the framework. Never called implicitly —
 * the caller decides when a jar-drop tier is appropriate for the deployment.
 */
public fun Context.discoverServicePlugins(): List<PluginFactory<*>> =
    classLoaderPlugin.services(PluginFactory::class).toList()
