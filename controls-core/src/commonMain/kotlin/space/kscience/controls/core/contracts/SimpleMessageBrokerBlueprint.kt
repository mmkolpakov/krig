package space.kscience.controls.core.contracts

/**
 * A simple data-holding implementation of [MessageBrokerBlueprint].
 */
public data class SimpleMessageBrokerBlueprint<B : MessageBroker>(
    override val id: String,
    override val driver: MessageBrokerDriver<B>,
) : MessageBrokerBlueprint<B>