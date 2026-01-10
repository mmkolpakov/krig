package space.kscience.controls.connectivity.addressing

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import space.kscience.controls.api.addressing.TransportAddress

/**
 * Represents a Unix Domain Socket or Named Pipe address.
 * @property path The file system path to the socket.
 */
@Serializable
@SerialName("ipc")
public data class IpcAddress(val path: String) : TransportAddress