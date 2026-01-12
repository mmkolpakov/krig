package space.kscience.controls.core.serialization

import kotlinx.serialization.modules.SerializersModule
import space.kscience.dataforge.context.Plugin

/**
 * A marker interface for Plugins that wish to register their own data types (polymorphic subclasses)
 * into the global serialization system.
 *
 * Any [space.kscience.dataforge.context.Plugin] implementing this interface will be automatically discovered by the [space.kscience.controls.core.serialization.SerializationPlugin],
 * and its [serializersModule] will be included in the shared [kotlinx.serialization.json.Json] instance.
 */
public interface SerializationContributor : Plugin {
    /**
     * The [kotlinx.serialization.modules.SerializersModule] containing polymorphic definitions for the feature provided by this plugin.
     */
    public val serializersModule: SerializersModule
}