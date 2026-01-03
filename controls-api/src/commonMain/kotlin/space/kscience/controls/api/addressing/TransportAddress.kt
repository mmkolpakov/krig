package space.kscience.controls.api.addressing

import kotlinx.serialization.Polymorphic
import kotlinx.serialization.Serializable

/**
 * An interface representing the physical address details for a specific transport protocol.
 * This allows for type-safe handling of different connection parameters.
 */
@Polymorphic
public interface TransportAddress

/**
 * Represents the physical address for a TCP-based connection.
 * @property host The hostname or IP address.
 * @property port The TCP port number.
 */
@Serializable
public data class TcpAddress(val host: String, val port: Int) : TransportAddress