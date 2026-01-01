package space.kscience.controls.core.contracts

import kotlinx.coroutines.flow.Flow
import space.kscience.controls.core.messages.DeviceMessage
import space.kscience.dataforge.names.Name

/**
 * A contract for a message bus supporting publish-subscribe patterns.
 *
 * ### Topic Matching
 * Topic patterns can include wildcards to subscribe to multiple topics:
 * - `*` (asterisk): Matches a single token in the topic `Name`.
 * - `**` (double asterisk): Matches zero or more tokens at the end of a topic `Name`.
 *
 * **Examples:**
 * - `a.b.c`: Matches only the exact topic `a.b.c`.
 * - `a.*.c`: Matches `a.x.c`, `a.y.c`, but not `a.b.x.c`.
 * - `a.b.**`: Matches `a.b`, `a.b.c`, `a.b.c.d`, etc.
 */
public interface MessageBroker {
    /**
     * Publishes a message to a specific topic. The message will be delivered to all
     * active subscribers of that topic. This is a "fire-and-forget" operation from the caller's perspective.
     *
     * @param topic The hierarchical topic name to publish to.
     * @param message The [DeviceMessage] to send.
     */
    public suspend fun publish(topic: Name, message: DeviceMessage)

    /**
     * Subscribes to a topic pattern.
     *
     * @param topicPattern The topic pattern to subscribe to (e.g., "device.motor1.**").
     * @return A **hot** [Flow] of [DeviceMessage]s matching the pattern. The flow remains active and receives
     *         messages regardless of whether it has active collectors.
     */
    public fun subscribe(topicPattern: Name): Flow<DeviceMessage>
}