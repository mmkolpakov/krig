package space.kscience.controls.connectivity

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
 * A plugin providing Connectivity features (Peer connections, Property bindings, Composition).
 * It registers the [connectivitySerializersModule] into the global serialization context.
 */
public class ConnectivityFeaturePlugin : AbstractPlugin(), SerializationContributor {
    override val tag: PluginTag get() = Companion.tag

    override val serializersModule: SerializersModule get() = connectivitySerializersModule

    override fun content(target: String): Map<Name, Any> = when (target) {
        CapabilityFactory.TARGET -> mapOf(
            CompositionSpec.name to CapabilityFactory<CompositionFeature, CompositionCapability> { _, _, _ -> TODO("Implement CompositionCapability") },
            ConnectivitySpec.name to CapabilityFactory<ConnectivityFeature, ConnectivityCapability> { _, _, _ -> TODO("Implement ConnectivityCapability") },
            ChildBindingsSpec.name to CapabilityFactory<ChildBindingsFeature, ChildBindingsCapability> { _, _, _ -> TODO("Implement ChildBindingsCapability") },
            BinaryDataSpec.name to CapabilityFactory<BinaryDataFeature, BinaryDataCapability> { _, _, _ -> TODO("Implement BinaryDataCapability") },
            RemoteMirrorSpec.name to CapabilityFactory<RemoteMirrorFeature, RemoteMirrorCapability> { _, _, _ -> TODO("Implement RemoteMirrorCapability") }
        )
        else -> emptyMap()
    }

    public companion object : PluginFactory<ConnectivityFeaturePlugin> {
        override val tag: PluginTag = PluginTag("feature.connectivity", group = PluginTag.DATAFORGE_GROUP)
        override fun build(context: Context, meta: Meta): ConnectivityFeaturePlugin = ConnectivityFeaturePlugin()
    }
}