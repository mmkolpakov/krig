package space.kscience.controls.connectivity

import space.kscience.controls.connectivity.config.AddressSource
import space.kscience.controls.connectivity.config.FailoverStrategy
import space.kscience.controls.api.spec.ResiliencePolicy

/**
 * A blueprint for a [PeerConnection]. This is a stateless factory that defines
 * how to create a peer connection instance, including support for service discovery, failover, and resilience.
 *
 * @param P The type of the peer connection this blueprint creates.
 */
public interface PeerBlueprint<P : PeerConnection> {
    /**
     * A unique identifier for this blueprint, typically derived from the property name in the DSL.
     */
    public val id: String

    /**
     * The source of network addresses for this peer. Can be static or dynamic.
     */
    public val addressSource: AddressSource

    /**
     * The strategy to use for selecting an address when multiple are available or for failover.
     */
    public val failoverStrategy: FailoverStrategy

    /**
     * An optional set of resilience policies to apply to this connection.
     * The runtime is responsible for implementing these policies (e.g., by wrapping the connection proxy).
     */
    public val resiliencePolicy: ResiliencePolicy? get() = null

    /**
     * The driver responsible for creating the [PeerConnection] instance.
     */
    public val driver: PeerDriver<P>
}
