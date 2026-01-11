package space.kscience.controls.alarms

import kotlinx.serialization.modules.SerializersModule
import space.kscience.controls.api.serialization.SerializationContributor
import space.kscience.controls.core.capabilities.CapabilityFactory
import space.kscience.dataforge.context.AbstractPlugin
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.context.PluginFactory
import space.kscience.dataforge.context.PluginTag
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.Name

/**
 * A plugin providing Alarms & Events capabilities.
 * It registers the [alarmsSerializersModule] into the global serialization context
 * and provides the factory for creating the Alarms capability.
 */
public class AlarmsFeaturePlugin : AbstractPlugin(), SerializationContributor {
    override val tag: PluginTag get() = Companion.tag

    override val serializersModule: SerializersModule get() = alarmsSerializersModule

    override fun content(target: String): Map<Name, Any> = when (target) {
        CapabilityFactory.TARGET -> mapOf(
            AlarmsSpec.name to CapabilityFactory<AlarmsFeature, AlarmSource> { _, device, feature ->
                TODO("Implement AlarmSourceImpl(device, feature)")
            }
        )
        else -> emptyMap()
    }

    public companion object : PluginFactory<AlarmsFeaturePlugin> {
        override val tag: PluginTag = PluginTag("feature.alarms", group = PluginTag.DATAFORGE_GROUP)
        override fun build(context: Context, meta: Meta): AlarmsFeaturePlugin = AlarmsFeaturePlugin()
    }
}