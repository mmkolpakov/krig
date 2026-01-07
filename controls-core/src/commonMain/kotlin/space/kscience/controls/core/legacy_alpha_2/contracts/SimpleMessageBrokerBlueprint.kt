package space.kscience.controls.core.legacy_alpha_2.contracts

/**
 * A simple data-holding implementation of [MessageBrokerBlueprint].
 */
public data class SimpleMessageBrokerBlueprint<B : MessageBroker>(
    override val id: String,
    override val driver: MessageBrokerDriver<B>,
) : MessageBrokerBlueprint<B>