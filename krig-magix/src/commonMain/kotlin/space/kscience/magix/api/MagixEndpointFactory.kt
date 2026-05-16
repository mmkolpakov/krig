package space.kscience.magix.api

import space.kscience.dataforge.context.Context
import space.kscience.dataforge.context.Factory
import space.kscience.dataforge.meta.Meta

/**
 * A factory for creating [MagixEndpoint] instances from a [Meta] configuration.
 * This functional interface allows for a plug-in architecture for different Magix transport implementations.
 * Implementations of this factory are discovered by plugins via the [TARGET] constant.
 */
public fun interface MagixEndpointFactory : Factory<MagixEndpoint> {
    /**
     * Builds a [MagixEndpoint] instance.
     *
     * @param context The context in which the endpoint will operate.
     * @param meta The configuration specific to this endpoint instance.
     * @return A new instance of [MagixEndpoint].
     */
    override fun build(context: Context, meta: Meta): MagixEndpoint

    public companion object {
        /**
         * The target string used by the plugin system to discover [MagixEndpointFactory] providers.
         */
        public const val TARGET: String = "magix.endpoint.factory"
    }
}