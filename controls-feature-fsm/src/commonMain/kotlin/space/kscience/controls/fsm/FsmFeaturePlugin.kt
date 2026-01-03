package space.kscience.controls.fsm

import kotlinx.serialization.modules.SerializersModule
import space.kscience.controls.api.serialization.SerializationContributor
import space.kscience.dataforge.context.AbstractPlugin
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.context.PluginFactory
import space.kscience.dataforge.context.PluginTag
import space.kscience.dataforge.meta.Meta

/**
 * A plugin providing Finite State Machine capabilities (Lifecycle, Operational FSM) to the runtime.
 * It registers the [fsmSerializersModule] into the global serialization context.
 */
public class FsmFeaturePlugin : AbstractPlugin(), SerializationContributor {
    override val tag: PluginTag get() = Companion.tag

    override val serializersModule: SerializersModule get() = fsmSerializersModule

    public companion object : PluginFactory<FsmFeaturePlugin> {
        override val tag: PluginTag = PluginTag("feature.fsm", group = PluginTag.DATAFORGE_GROUP)
        override fun build(context: Context, meta: Meta): FsmFeaturePlugin = FsmFeaturePlugin()
    }
}