package space.kscience.controls.services.discovery

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import space.kscience.controls.core.addressing.Address
import space.kscience.controls.core.addressing.AddressUpdateEvent
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.context.Plugin
import space.kscience.dataforge.context.PluginFactory
import space.kscience.dataforge.context.PluginTag
import space.kscience.dataforge.meta.Meta

/**
 * A contract for a service that can discover network addresses of peer connection endpoints by a service ID.
 * This is essential for dynamic, distributed systems where endpoint locations are not known at compile time.
 * Implementations could use mDNS, a central registry, or other discovery mechanisms.
 */
public interface DiscoveryService : Plugin {
    override val tag: PluginTag get() = Companion.tag

    /**
     * Returns a hot [Flow] of [AddressUpdateEvent]s for a given service ID.
     * The flow emits events as addresses for the service become available or unavailable.
     *
     * @param serviceId The unique identifier of the service to discover.
     */
    public fun discover(serviceId: String): Flow<AddressUpdateEvent>

    public companion object : PluginFactory<DiscoveryService> {
        override val tag: PluginTag = PluginTag("device.discovery", group = PluginTag.DATAFORGE_GROUP)

        /**
         * The default implementation of this factory throws an error, as a concrete implementation
         * must be provided by a runtime or a dedicated service discovery module.
         */
        override fun build(context: Context, meta: Meta): DiscoveryService {
            error("DiscoveryService is a service interface and requires a runtime-specific implementation.")
        }
    }
}