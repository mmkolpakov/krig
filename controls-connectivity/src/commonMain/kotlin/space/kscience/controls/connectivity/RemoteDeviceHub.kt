package space.kscience.controls.connectivity

import kotlinx.coroutines.flow.StateFlow
import space.kscience.controls.core.contracts.DeviceHub

/**
 * A contract for a proxy to a remote [space.kscience.controls.core.contracts.DeviceHub].
 * This interface allows treating a remote hub as if it were local, abstracting away the
 * underlying communication mechanism. It extends the base hub contract with
 * connection management capabilities.
 */
public interface RemoteDeviceHub : DeviceHub {
    /**
     * The unique identifier of the remote hub this proxy connects to.
     */
    public val hubId: String

    /**
     * A [StateFlow] indicating the current connection status to the remote hub.
     */
    public val isConnected: StateFlow<Boolean>

    /**
     * Establishes the connection to the remote hub. This is a suspendable operation.
     * Throws an exception if the connection cannot be established.
     */
    public suspend fun connect()

    /**
     * Terminates the connection to the remote hub.
     */
    public suspend fun disconnect()
}