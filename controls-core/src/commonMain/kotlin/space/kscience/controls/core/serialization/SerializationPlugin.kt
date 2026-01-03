package space.kscience.controls.core.serialization

import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import space.kscience.controls.api.serialization.SerializationContributor
import space.kscience.dataforge.context.*
import space.kscience.dataforge.meta.Meta

/**
 * A central infrastructure plugin that aggregates serialization configurations from all other loaded plugins.
 *
 * It scans the [Context] for any plugins implementing [space.kscience.controls.api.serialization.SerializationContributor] and combines their
 * [SerializersModule]s with the core module. This results in a single, context-aware [Json] instance
 * capable of handling all polymorphic types (DeviceMessage, Feature, etc.) present in the runtime.
 */
public class SerializationPlugin : AbstractPlugin() {
    override val tag: PluginTag get() = Companion.tag

    /**
     * Lazily finds all plugins in the context that implement [space.kscience.controls.api.serialization.SerializationContributor].
     */
    private val contributors: Collection<SerializationContributor> by lazy {
        context.plugins.filterIsInstance<SerializationContributor>()
    }

    /**
     * Constructs the final [SerializersModule] by merging the core module with modules
     * from all discovered contributors.
     */
    public val jsonSerializersModule: SerializersModule by lazy {
        SerializersModule {
            // 1. Include base types from controls-core
            include(controlsCoreSerializersModule)

            // 2. Include modules from all loaded feature plugins
            contributors.forEach { contributor ->
                include(contributor.serializersModule)
            }
        }
    }

    /**
     * The shared [Json] instance configured with the aggregated [jsonSerializersModule].
     * This instance should be used by all infrastructure components (loggers, network transports)
     * that need to serialize polymorphic hierarchies.
     */
    public val json: Json by lazy {
        Json {
            serializersModule = jsonSerializersModule
            ignoreUnknownKeys = true
            encodeDefaults = true
            classDiscriminator = "type"
            prettyPrint = true
        }
    }

    public companion object : PluginFactory<SerializationPlugin> {
        override val tag: PluginTag = PluginTag("controls.serialization", group = PluginTag.DATAFORGE_GROUP)

        override fun build(context: Context, meta: Meta): SerializationPlugin = SerializationPlugin()
    }
}

/**
 * A convenience extension to retrieve the context-aware [Json] instance.
 * Throws an exception if [SerializationPlugin] is not installed.
 */
public val Context.json: Json get() = request(SerializationPlugin).json