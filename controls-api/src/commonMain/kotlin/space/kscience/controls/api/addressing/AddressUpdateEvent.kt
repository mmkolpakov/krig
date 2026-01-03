package space.kscience.controls.api.addressing

import kotlinx.serialization.Serializable

/**
 * An event representing a change in the set of available addresses for a discovered service.
 */
@Serializable
public sealed interface AddressUpdateEvent {
    public val serviceId: String
    public val address: Address

    /**
     * Fired when a new address for a service is discovered.
     */
    @Serializable
    public data class AddressUp(
        override val serviceId: String,
        override val address: Address,
    ) : AddressUpdateEvent

    /**
     * Fired when a previously available address for a service is no longer reachable.
     */
    @Serializable
    public data class AddressDown(
        override val serviceId: String,
        override val address: Address,
    ) : AddressUpdateEvent
}