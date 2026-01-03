package space.kscience.controls.composite.magix

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.PolymorphicSerializer
import space.kscience.controls.core.contracts.MessageBroker
import space.kscience.controls.api.messages.DeviceMessage
import space.kscience.dataforge.misc.DFExperimental
import space.kscience.dataforge.names.Name
import space.kscience.magix.api.*

/**
 * A [MessageBroker] implementation that uses a [MagixEndpoint] for communication.
 * It translates the broker's topic-based publish/subscribe old to Magix's source/target endpoint old.
 *
 * @property coroutineScope The scope for managing subscription jobs.
 * @property endpoint The underlying [MagixEndpoint] used for communication.
 * @property localHubId The unique identifier of the local hub, used as the `sourceEndpoint` in outgoing messages.
 * @property originTarget The Magix `targetEndpoint` used for broadcast messages. Typically a well-known name for the system.
 */
public class MagixMessageBroker(
    private val coroutineScope: CoroutineScope,
    private val endpoint: MagixEndpoint,
    private val localHubId: Name,
    private val originTarget: Name
) : MessageBroker {

    public companion object {
        /**
         * A MagixFormat specifically for serializing and deserializing [DeviceMessage] instances.
         */
        public val deviceMessageFormat: MagixFormat<DeviceMessage> = MagixFormat(
            PolymorphicSerializer(DeviceMessage::class),
            setOf("controls-kt")
        )
    }

    override suspend fun publish(topic: Name, message: DeviceMessage) {
        endpoint.send(
            format = deviceMessageFormat,
            payload = message,
            source = localHubId,
            target = originTarget,
            topic = topic
        )
    }

    /**
     * Subscribes to a topic pattern.
     *
     * @param topicPattern The topic pattern to subscribe to (e.g., "device.motor1.**").
     * @return A **hot** [Flow] of [DeviceMessage]s matching the pattern.
     */
    @OptIn(DFExperimental::class)
    override fun subscribe(topicPattern: Name): Flow<DeviceMessage> {
        return endpoint.subscribe(
            format = deviceMessageFormat,
            targetFilter = listOf(originTarget),
            topicPattern = topicPattern
        ).map { it.second }
    }

    /**
     * Creates a bidirectional bridge that forwards messages between this broker's endpoint and a target endpoint.
     * This is useful for creating gateways between different Magix networks or transport protocols.
     *
     * @param targetEndpoint The remote endpoint to bridge with.
     * @param forwardFilter A filter for messages going from this broker's endpoint to the target.
     * @param backwardFilter A filter for messages coming from the target to this broker's endpoint.
     * @return A [Job] that manages the bridge. Cancelling the job will terminate the portal.
     */
    public fun createPortal(
        targetEndpoint: MagixEndpoint,
        forwardFilter: MagixMessageFilter = MagixMessageFilter.ALL,
        backwardFilter: MagixMessageFilter = MagixMessageFilter.ALL,
    ): Job = coroutineScope.launch {
        // Forward messages from local to remote
        endpoint.subscribe(forwardFilter).onEach { message: MagixMessage ->
            targetEndpoint.broadcast(message)
        }.launchIn(this)

        // Forward messages from remote to local
        targetEndpoint.subscribe(backwardFilter).onEach { message ->
            // Only process messages with the correct format
            if (deviceMessageFormat.formats.contains(message.format)) {
//                TODO refactor, remove magixJson
//                val deviceMessage = MagixEndpoint.magixJson.decodeFromJsonElement(
//                    PolymorphicSerializer(DeviceMessage::class),
//                    message.payload
//                )
//                val topic = deviceMessage.sourceDevice?.device ?: Name.EMPTY
//                publish(topic, deviceMessage)
            }
        }.launchIn(this)
    }
}