package space.kscience.controls.automation

import kotlinx.serialization.modules.SerializersModule
import space.kscience.controls.core.serialization.SerializationContributor
import space.kscience.dataforge.context.AbstractPlugin
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.context.PluginFactory
import space.kscience.dataforge.context.PluginTag
import space.kscience.dataforge.meta.Meta

/**
 * A plugin providing Automation features (Transaction Plans, Tasks) to the runtime.
 * It registers the [automationSerializersModule] into the global serialization context.
 */
public class AutomationFeaturePlugin : AbstractPlugin(), SerializationContributor {
    override val tag: PluginTag get() = Companion.tag

    override val serializersModule: SerializersModule get() = automationSerializersModule

    public companion object : PluginFactory<AutomationFeaturePlugin> {
        override val tag: PluginTag = PluginTag("feature.automation", group = PluginTag.DATAFORGE_GROUP)
        override fun build(context: Context, meta: Meta): AutomationFeaturePlugin = AutomationFeaturePlugin()
    }
}