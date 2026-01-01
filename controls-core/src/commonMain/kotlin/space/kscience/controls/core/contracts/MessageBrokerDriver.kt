package space.kscience.controls.core.contracts

import space.kscience.dataforge.context.Context
import space.kscience.dataforge.meta.Meta

/**
 * A factory responsible for creating an instance of a [MessageBroker].
 * This allows for pluggable message bus implementations (e.g., in-memory, Magix, MQTT).
 *
 * @param B The type of the message broker this driver creates.
 */
public fun interface MessageBrokerDriver<B : MessageBroker> {
    /**
     * Creates a new message broker instance.
     * @param context The DataForge context for the broker.
     * @param meta The configuration meta for the broker.
     * @return A new instance of the message broker.
     */
    public fun create(context: Context, meta: Meta): B
}

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

/**
 * A simple data-holding implementation of [MessageBrokerBlueprint].
 */
public data class SimpleMessageBrokerBlueprint<B : MessageBroker>(
    override val id: String,
    override val driver: MessageBrokerDriver<B>,
) : MessageBrokerBlueprint<B>