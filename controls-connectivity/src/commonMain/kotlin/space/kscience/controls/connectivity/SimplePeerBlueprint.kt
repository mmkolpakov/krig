package space.kscience.controls.connectivity

import space.kscience.controls.api.connectivity.AddressSource
import space.kscience.controls.api.connectivity.FailoverStrategy
import space.kscience.controls.api.spec.ResiliencePolicy

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