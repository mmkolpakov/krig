package space.kscience.controls.core.contracts

/**
 * A blueprint for a [MessageBroker]. This is a stateless factory that defines
 * how to create a message broker instance.
 *
 * @param B The type of the message broker this blueprint creates.
 */
public interface MessageBrokerBlueprint<B : MessageBroker> {
    /**
     * A unique identifier for this blueprint.
     */
    public val id: String

    /**
     * The driver responsible for creating the [MessageBroker] instance.
     */
    public val driver: MessageBrokerDriver<B>
}