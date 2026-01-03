package space.kscience.controls.telemetry

import kotlinx.serialization.modules.SerializersModule
import space.kscience.controls.api.serialization.SerializationContributor
import space.kscience.dataforge.context.AbstractPlugin
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.context.PluginFactory
import space.kscience.dataforge.context.PluginTag
import space.kscience.dataforge.meta.Meta

/**
 * A plugin providing High-Performance Telemetry capabilities (TelemetryPacket, DataSource).
 * It registers the [telemetrySerializersModule] into the global serialization context.
 */
public class TelemetryFeaturePlugin : AbstractPlugin(), SerializationContributor {
    override val tag: PluginTag get() = Companion.tag

    override val serializersModule: SerializersModule get() = telemetrySerializersModule

    public companion object : PluginFactory<TelemetryFeaturePlugin> {
        override val tag: PluginTag = PluginTag("feature.telemetry", group = PluginTag.DATAFORGE_GROUP)
        override fun build(context: Context, meta: Meta): TelemetryFeaturePlugin = TelemetryFeaturePlugin()
    }
}