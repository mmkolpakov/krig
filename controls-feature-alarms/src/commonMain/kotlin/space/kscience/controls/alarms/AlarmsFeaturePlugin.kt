package space.kscience.controls.alarms

import kotlinx.serialization.modules.SerializersModule
import space.kscience.controls.core.serialization.SerializationContributor
import space.kscience.dataforge.context.AbstractPlugin
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.context.PluginFactory
import space.kscience.dataforge.context.PluginTag
import space.kscience.dataforge.meta.Meta

/**
 * A plugin providing Alarms & Events capabilities.
 * It registers the [alarmsSerializersModule] into the global serialization context.
 */
public class AlarmsFeaturePlugin : AbstractPlugin(), SerializationContributor {
    override val tag: PluginTag get() = Companion.tag

    override val serializersModule: SerializersModule get() = alarmsSerializersModule

    public companion object : PluginFactory<AlarmsFeaturePlugin> {
        override val tag: PluginTag = PluginTag("feature.alarms", group = PluginTag.DATAFORGE_GROUP)
        override fun build(context: Context, meta: Meta): AlarmsFeaturePlugin = AlarmsFeaturePlugin()
    }
}