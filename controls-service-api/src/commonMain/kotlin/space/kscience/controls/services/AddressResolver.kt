package space.kscience.controls.services

import space.kscience.controls.api.addressing.TransportAddress
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.context.Plugin
import space.kscience.dataforge.context.PluginFactory
import space.kscience.dataforge.context.PluginTag
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.Name

/**
 * A contract for a service that resolves a logical hub identifier (`hubId`) into a physical [TransportAddress].
 * This is a critical component for service discovery in a distributed system, abstracting away the mechanism
 * of how addresses are found (e.g., from static configuration, DNS-SRV, a discovery service like Consul, etc.).
 */
public interface AddressResolver : Plugin {
    override val tag: PluginTag get() = Companion.tag

    /**
     * Resolves a logical route (hub identifier hierarchy) into a physical [TransportAddress].
     *
     * @param route The hierarchical identifier of the target hub/node.
     */
    public suspend fun resolve(route: Name): TransportAddress?

    public companion object : PluginFactory<AddressResolver> {
        override val tag: PluginTag = PluginTag("device.address.resolver", group = PluginTag.DATAFORGE_GROUP)

        /**
         * The default factory throws an error, as a concrete implementation must be provided by a runtime
         * or a dedicated service discovery module.
         */
        override fun build(context: Context, meta: Meta): AddressResolver {
            error("AddressResolver is a service interface and requires a runtime-specific implementation.")
        }
    }
}