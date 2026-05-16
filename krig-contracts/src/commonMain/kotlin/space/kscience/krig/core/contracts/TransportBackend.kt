package space.kscience.krig.core.contracts

import kotlinx.coroutines.flow.StateFlow
import space.kscience.krig.api.lifecycle.ConnectionState
import space.kscience.krig.core.UnstableKrigForSubclassing

/**
 * Marks a [DeviceBackend] that owns a transport — hardware driver, remote RPC, network
 * socket. Exposes observable connectivity and explicit connect / disconnect hooks.
 * Pipeline interceptors use [connectionState] to reject reads / writes on a broken link.
 */
@SubclassOptInRequired(UnstableKrigForSubclassing::class)
public interface TransportBackend : DeviceBackend {
    public val connectionState: StateFlow<ConnectionState>

    /** Opens the transport. Idempotent. */
    public suspend fun connect()

    /** Tears down the transport. Idempotent. */
    public suspend fun disconnect()
}
