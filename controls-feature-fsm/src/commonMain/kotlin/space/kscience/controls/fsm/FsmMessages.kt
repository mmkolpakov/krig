package space.kscience.controls.fsm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import space.kscience.controls.api.addressing.Address
import space.kscience.controls.api.identifiers.CorrelationId
import space.kscience.controls.api.messages.DeviceMessage
import space.kscience.dataforge.names.Name
import kotlin.time.Instant

/**
 * Notifies about a change in the device's lifecycle state machine.
 *
 * @param oldStateName The name of the state that was exited. Can be null if this is the initial transition.
 * @param newStateName The name of the state that was entered.
 * @param sourceDevice The name of the device whose state changed. Mandatory.
 */
@Serializable
@SerialName("lifecycle.stateChanged")
public data class LifecycleStateChangedMessage(
    override val time: Instant,
    public val oldStateName: String?,
    public val newStateName: String,
    override val sourceDevice: Address,
    override val targetDevice: Address? = null,
    override val requestId: String? = null,
    override val correlationId: CorrelationId? = null,
) : DeviceMessage {
    override fun changeSource(block: (Name) -> Name): LifecycleStateChangedMessage =
        copy(sourceDevice = sourceDevice.copy(device = block(sourceDevice.device)))
}