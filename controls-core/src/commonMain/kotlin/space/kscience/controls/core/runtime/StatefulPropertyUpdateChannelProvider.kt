package space.kscience.controls.core.runtime

import kotlinx.coroutines.channels.Channel
import space.kscience.controls.core.contracts.Device

/**
 * A capability interface defining the contract for a service that provides
 * instance-specific update channels for devices. This abstraction is key to
 * the Dependency Inversion Principle, allowing the DSL to depend on this
 * contract rather than a concrete runtime implementation.
 */
public interface StatefulPropertyUpdateChannelProvider {
    /**
     * Retrieves or creates a dedicated update channel for the given device instance.
     *
     * @param device The device for which the channel is requested.
     * @return A [Channel] for posting [StateUpdate] messages.
     */
    public suspend fun getPropertyUpdateChannel(device: Device): Channel<StateUpdate>
}