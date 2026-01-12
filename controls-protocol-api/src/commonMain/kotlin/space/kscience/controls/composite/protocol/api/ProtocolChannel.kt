package space.kscience.controls.composite.protocol.api

import space.kscience.controls.composite.ports.Port
import space.kscience.controls.core.contracts.DeviceConnection

/**
 * Represents an active communication channel (session) established over a [Port].
 * Unlike the stateless [ProtocolAdapter], a [ProtocolChannel] can maintain state,
 * such as transaction counters, authentication tokens, or handshake status.
 */
public interface ProtocolChannel : DeviceConnection