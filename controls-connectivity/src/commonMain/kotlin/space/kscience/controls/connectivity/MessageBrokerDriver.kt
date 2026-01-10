package space.kscience.controls.connectivity

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
