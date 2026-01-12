package space.kscience.controls.telemetry

import kotlinx.serialization.modules.SerializersModule
import space.kscience.controls.core.serialization.SerializationContributor
import space.kscience.controls.core.capabilities.CapabilityFactory
import space.kscience.dataforge.context.AbstractPlugin
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.context.PluginFactory
import space.kscience.dataforge.context.PluginTag
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.Name

/**
 * A plugin providing High-Performance Telemetry capabilities.
 */
public class TelemetryFeaturePlugin : AbstractPlugin(), SerializationContributor {
    override val tag: PluginTag get() = Companion.tag

    override val serializersModule: SerializersModule get() = telemetrySerializersModule

    override fun content(target: String): Map<Name, Any> = when (target) {
        CapabilityFactory.TARGET -> mapOf(
            TelemetrySpec.name to CapabilityFactory<TelemetryFeature, TelemetrySource> { _, _, _ ->
                TODO("Implement TelemetrySourceImpl")
            },
            DataSourceSpec.name to CapabilityFactory<DataSourceFeature, DataSourceCapability> { _, _, _ ->
                TODO("Implement DataSourceImpl")
            }
        )
        else -> emptyMap()
    }

    public companion object : PluginFactory<TelemetryFeaturePlugin> {
        override val tag: PluginTag = PluginTag("feature.telemetry", group = PluginTag.DATAFORGE_GROUP)
        override fun build(context: Context, meta: Meta): TelemetryFeaturePlugin = TelemetryFeaturePlugin()
    }
}