package space.kscience.controls.api.addressing

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Represents a Unix Domain Socket or Named Pipe address.
 * @property path The file system path to the socket.
 */
@Serializable
@SerialName("ipc")
public data class IpcAddress(val path: String) : TransportAddress