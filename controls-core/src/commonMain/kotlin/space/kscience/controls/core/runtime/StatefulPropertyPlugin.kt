package space.kscience.controls.core.runtime

import space.kscience.dataforge.context.Context
import space.kscience.dataforge.context.Plugin
import space.kscience.dataforge.context.PluginFactory
import space.kscience.dataforge.context.PluginTag
import space.kscience.dataforge.meta.Meta

/**
 * A formal [Plugin] interface for the stateful property management system.
 */
public interface StatefulPropertyPlugin : StatefulPropertyUpdateChannelProvider, Plugin {
    public companion object : PluginFactory<StatefulPropertyPlugin> {
        override val tag: PluginTag = PluginTag("statefulPropertyManager", group = PluginTag.DATAFORGE_GROUP)
        override fun build(context: Context, meta: Meta): StatefulPropertyPlugin {
            error("StatefulPropertyPlugin is a service interface and requires a runtime implementation.")
        }
    }
}