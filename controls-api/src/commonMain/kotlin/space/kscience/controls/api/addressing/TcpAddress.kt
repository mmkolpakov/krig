package space.kscience.controls.api.addressing

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Represents the physical address for a TCP-based connection.
 * @property host The hostname or IP address.
 * @property port The TCP port number.
 */
@Serializable
@SerialName("tcp")
public data class TcpAddress(val host: String, val port: Int) : TransportAddress