package space.kscience.controls.core.connectivity

import space.kscience.controls.core.connectivity.AddressSource
import space.kscience.controls.core.spec.ResiliencePolicy

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

/**
 * A simple data-holding implementation of [PeerBlueprint].
 */
public data class SimplePeerBlueprint<P : PeerConnection>(
    override val id: String,
    override val addressSource: AddressSource,
    override val failoverStrategy: FailoverStrategy,
    override val resiliencePolicy: ResiliencePolicy? = null,
    override val driver: PeerDriver<P>,
) : PeerBlueprint<P>