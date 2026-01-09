package space.kscience.controls.services.transport

/**
 * A base exception for errors related to peer-to-peer communication.
 */
public open class PeerConnectionException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * An exception thrown when a peer-to-peer operation does not complete within the specified timeout.
 */
public class PeerConnectionTimeoutException(message: String, cause: Throwable? = null) : PeerConnectionException(message, cause)
