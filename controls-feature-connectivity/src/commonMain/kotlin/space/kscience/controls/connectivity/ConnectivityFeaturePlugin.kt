package space.kscience.controls.connectivity

import kotlinx.serialization.modules.SerializersModule
import space.kscience.controls.api.serialization.SerializationContributor
import space.kscience.dataforge.context.AbstractPlugin
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.context.PluginFactory
import space.kscience.dataforge.context.PluginTag
import space.kscience.dataforge.meta.Meta

/**
 * A plugin providing Connectivity features (Peer connections, Property bindings, Composition).
 * It registers the [connectivitySerializersModule] into the global serialization context.
 */
public class ConnectivityFeaturePlugin : AbstractPlugin(), SerializationContributor {
    override val tag: PluginTag get() = Companion.tag

    override val serializersModule: SerializersModule get() = connectivitySerializersModule

    public companion object : PluginFactory<ConnectivityFeaturePlugin> {
        override val tag: PluginTag = PluginTag("feature.connectivity", group = PluginTag.DATAFORGE_GROUP)
        override fun build(context: Context, meta: Meta): ConnectivityFeaturePlugin = ConnectivityFeaturePlugin()
    }
}