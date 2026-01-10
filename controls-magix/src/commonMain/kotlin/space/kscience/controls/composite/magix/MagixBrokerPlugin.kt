package space.kscience.controls.composite.magix

import space.kscience.controls.connectivity.MessageBroker
import space.kscience.dataforge.context.*
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.get
import space.kscience.dataforge.meta.string
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName
import space.kscience.dataforge.names.parseAsName
import space.kscience.magix.api.MagixEndpoint
import space.kscience.magix.api.MagixEndpointFactory

/**
 * A DataForge plugin to provide and configure Magix-based communication services.
 * It acts as a factory for [MagixMessageBroker] and [MagixPeerSignalingService].
 * The configuration for the underlying [MagixEndpoint] must be provided in this plugin's meta.
 * This plugin uses the DataForge service locator pattern to find available [MagixEndpointFactory] plugins.
 *
 * Example configuration:
 * ```kotlin
 * // It is assumed that a plugin for the 'rsocket.ws' type is available in the context.
 * plugin(MagixBrokerPlugin) {
 *     "hubId" put "my-control-hub"
 *     "endpoint" {
 *         "type" put "rsocket.ws"
 *         "host" put "localhost"
 *         "port" put 7777
 *     }
 * }
 * ```
 *
 * @param meta The configuration for this plugin. It must contain the `hubId` and an `endpoint` block.
 */
public class MagixBrokerPlugin(meta: Meta) : AbstractPlugin(meta) {
    override val tag: PluginTag get() = Companion.tag

    /**
     * The unique identifier for this hub within the Magix network.
     */
    public val hubId: String by meta.string { error("The 'hubId' is required in the MagixBrokerPlugin configuration.") }

    private lateinit var endpoint: MagixEndpoint

    /**
     * Lazily creates and holds the [MagixEndpoint] instance based on the plugin's configuration.
     * The endpoint is shared across all services created by this plugin. It dynamically discovers
     * the appropriate [MagixEndpointFactory] from the context.
     */
    private fun resolveEndpoint(): MagixEndpoint {
        val endpointMeta = meta["endpoint"] ?: error("Magix endpoint configuration is not provided.")
        val type = endpointMeta["type"].string ?: error("The 'type' of the Magix endpoint is not specified in the configuration.")

        val endpointFactories = context.gather<MagixEndpointFactory>(MagixEndpointFactory.TARGET)
        val factory = endpointFactories[type.asName()]
            ?: error("Unsupported Magix endpoint type: '$type'. No factory plugin found in the context for this type.")

        return factory.build(context, endpointMeta)
    }

    private fun getEndpoint(): MagixEndpoint {
        if (!::endpoint.isInitialized) {
            endpoint = resolveEndpoint()
        }
        return endpoint
    }

    /**
     * Creates a new [MagixMessageBroker] instance using the configured Magix endpoint.
     *
     * @param originTarget The Magix target for broadcast messages, defaulting to the hubId.
     * @return A configured instance of [MagixMessageBroker].
     */
    public fun broker(originTarget: Name = this.hubId.parseAsName()): MessageBroker =
        MagixMessageBroker(context, getEndpoint(), hubId.parseAsName(), originTarget)

    /**
     * Creates a new [MagixPeerSignalingService] instance for P2P signaling.
     * @return A configured instance of [MagixPeerSignalingService].
     */
    public fun createPeerSignalingService(): MagixPeerSignalingService = MagixPeerSignalingService(getEndpoint(), hubId.parseAsName())

    /**
     * Detaches the plugin and closes the underlying Magix endpoint if it has been initialized.
     * This ensures that network resources are properly released when the context is shut down.
     */
    override fun detach() {
        if (::endpoint.isInitialized) {
            endpoint.close()
        }
        super.detach()
    }

    public companion object : PluginFactory<MagixBrokerPlugin> {
        override val tag: PluginTag = PluginTag("broker.magix", group = PluginTag.DATAFORGE_GROUP)
        override fun build(context: Context, meta: Meta): MagixBrokerPlugin = MagixBrokerPlugin(meta)
    }
}