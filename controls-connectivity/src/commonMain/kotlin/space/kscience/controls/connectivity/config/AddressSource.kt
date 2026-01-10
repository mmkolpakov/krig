package space.kscience.controls.connectivity.config

import kotlinx.serialization.Polymorphic
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import space.kscience.controls.api.addressing.Address

/**
 * Defines the source of addresses for a peer connection, allowing for both static and dynamic configurations.
 */
@Polymorphic
public interface AddressSource

/**
 * A static, fixed list of addresses for a peer.
 * @property addresses The list of potential addresses.
 */
@Serializable
@SerialName("static")
public data class StaticAddressSource(val addresses: List<Address>) : AddressSource

/**
 * A dynamic source of addresses provided by a [DiscoveryService].
 * @property serviceId The unique identifier of the service to look up. The runtime will use a [DiscoveryService]
 *                     plugin to resolve this ID to a list of available addresses.
 */
@Serializable
@SerialName("discovered")
public data class DiscoveredAddressSource(val serviceId: String) : AddressSource