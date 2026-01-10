package space.kscience.controls.connectivity.services

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import space.kscience.dataforge.meta.Meta

/**
 * An event representing a change in the set of available hubs in the network.
 */
@Serializable
public sealed interface HubDiscoveryEvent {
    public val hubId: String

    /**
     * Fired when a new hub is discovered or an existing hub comes online.
     *
     * @property hubId The unique identifier of the hub.
     * @property address A network-specific address for the hub (e.g., "host:port", "topic", etc.).
     * @property meta Additional metadata about the hub, such as supported features or versions.
     */
    @Serializable
    public data class HubUp(
        override val hubId: String,
        val address: String,
        val meta: Meta = Meta.EMPTY
    ) : HubDiscoveryEvent

    /**
     * Fired when a previously discovered hub goes offline or is no longer reachable.
     *
     * @property hubId The unique identifier of the hub that was lost.
     */
    @Serializable
    public data class HubDown(override val hubId: String) : HubDiscoveryEvent
}


/**
 * A contract for a service that discovers other [CompositeDeviceHub] instances on the network.
 * This allows for dynamic, self-organizing clusters of control systems.
 * Implementations could use mDNS, ZooKeeper, Consul, or a static configuration file.
 */
public interface HubDiscoveryService {
    /**
     * A hot [Flow] that emits [HubDiscoveryEvent]s as hubs appear and disappear from the network.
     */
    public fun discover(): Flow<HubDiscoveryEvent>
}