package space.kscience.controls.connectivity

/**
 * A simple data-holding implementation of [MessageBrokerBlueprint].
 */
public data class SimpleMessageBrokerBlueprint<B : MessageBroker>(
    override val id: String,
    override val driver: MessageBrokerDriver<B>,
) : MessageBrokerBlueprint<B>