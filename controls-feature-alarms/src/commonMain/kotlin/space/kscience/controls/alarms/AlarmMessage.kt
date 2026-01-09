package space.kscience.controls.alarms

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import space.kscience.controls.api.addressing.Address
import space.kscience.controls.api.context.Principal
import space.kscience.controls.api.identifiers.CorrelationId
import space.kscience.controls.api.messages.DeviceMessage
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.Name
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * A sealed interface for all messages related to the Alarms & Events subsystem.
 */
@Serializable
public sealed interface AlarmMessage : DeviceMessage

/**
 * A message notifying that an alarm has transitioned to a new state.
 */
@Serializable
@SerialName("alarm.changed")
public data class AlarmStateChangedMessage(
    override val time: Instant,
    val alarmName: Name,
    val newState: AlarmState,
    val oldState: AlarmState?,
    override val source: Address,
    override val target: Address? = null,
    override val attributes: Meta,
) : AlarmMessage {
    fun changeSource(block: (Name) -> Name): AlarmStateChangedMessage =
        copy(source = source.copy(device = block(source.device)))
}

/**
 * A message notifying that an alarm has been acknowledged by a principal.
 */
@Serializable
@SerialName("alarm.ack")
public data class AlarmAcknowledgedMessage(
    override val time: Instant,
    val alarmName: Name,
    val principal: Principal,
    val comment: String?,
    override val source: Address,
    override val target: Address? = null,
    override val attributes: Meta,
) : AlarmMessage {
    fun changeSource(block: (Name) -> Name): AlarmAcknowledgedMessage =
        copy(source = source.copy(device = block(source.device)))
}

/**
 * A message notifying that an alarm has been temporarily suppressed (shelved).
 */
@Serializable
@SerialName("alarm.shelve")
public data class AlarmShelvedMessage(
    override val time: Instant,
    val alarmName: Name,
    val principal: Principal,
    val duration: Duration,
    val comment: String?,
    override val source: Address,
    override val target: Address? = null,
    override val attributes: Meta,
) : AlarmMessage {
    fun changeSource(block: (Name) -> Name): AlarmShelvedMessage =
        copy(source = source.copy(device = block(source.device)))
}