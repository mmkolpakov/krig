package space.kscience.krig.api.messages

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import space.kscience.krig.api.data.DataQuality
import space.kscience.krig.api.descriptors.ActionDescriptor
import space.kscience.krig.api.descriptors.PropertyDescriptor
import space.kscience.krig.api.faults.DeviceFault
import space.kscience.krig.api.faults.SerializableDeviceFailure
import space.kscience.krig.api.identifiers.BlueprintId
import space.kscience.krig.core.operations.HlcTimestamp
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.NameSerializer
import kotlin.time.Instant

/**
 * Notifies that a property's value has changed.
 */
@Serializable
@SerialName(DeviceMessageType.PropertyChanged)
public data class PropertyChangedMessage(
    override val time: Instant,
    @Serializable(with = NameSerializer::class)
    public val property: Name,
    public val value: Meta,
    @Serializable(with = NameSerializer::class)
    override val sourceDevice: Name,
    @Serializable(with = NameSerializer::class)
    override val targetDevice: Name? = null,
    override val requestId: String? = null,
    override val correlationId: String? = null,
    override val hlcTimestamp: HlcTimestamp? = null,
    public val quality: DataQuality? = null,
) : DeviceMessage {
    override val messageType: String get() = DeviceMessageType.PropertyChanged

    override fun changeSource(block: (Name) -> Name): PropertyChangedMessage =
        copy(sourceDevice = block(sourceDevice))

    override fun withHlcStamp(stamp: HlcTimestamp): PropertyChangedMessage = copy(hlcTimestamp = stamp)
}

/**
 * Indicates that an error occurred.
 */
@Serializable
@SerialName(DeviceMessageType.DeviceError)
public data class DeviceErrorMessage(
    override val time: Instant,
    public val failure: SerializableDeviceFailure,
    @Serializable(with = NameSerializer::class)
    override val sourceDevice: Name,
    @Serializable(with = NameSerializer::class)
    override val targetDevice: Name? = null,
    override val requestId: String?,
    override val correlationId: String? = null,
    override val hlcTimestamp: HlcTimestamp? = null,
) : DeviceMessage {
    override val messageType: String get() = DeviceMessageType.DeviceError

    override fun changeSource(block: (Name) -> Name): DeviceErrorMessage =
        copy(sourceDevice = block(sourceDevice))

    override fun withHlcStamp(stamp: HlcTimestamp): DeviceErrorMessage = copy(hlcTimestamp = stamp)
}

@Serializable
@SerialName(DeviceMessageType.ActionFault)
public data class ActionFaultMessage(
    override val time: Instant,
    public val fault: DeviceFault,
    @Serializable(with = NameSerializer::class)
    override val sourceDevice: Name,
    @Serializable(with = NameSerializer::class)
    override val targetDevice: Name? = null,
    override val requestId: String,
    override val correlationId: String? = null,
    override val hlcTimestamp: HlcTimestamp? = null,
) : ResponseMessage {
    override val messageType: String get() = DeviceMessageType.ActionFault

    override fun changeSource(block: (Name) -> Name): ActionFaultMessage =
        copy(sourceDevice = block(sourceDevice))

    override fun withHlcStamp(stamp: HlcTimestamp): ActionFaultMessage = copy(hlcTimestamp = stamp)
}

/**
 * Notifies that a new device has been attached to a hub, allowing clients to dynamically
 * update the device topology without polling.
 */
@Serializable
@SerialName(DeviceMessageType.DeviceAttached)
public data class DeviceAttachedMessage(
    override val time: Instant,
    public val deviceName: Name,
    public val blueprintId: BlueprintId,
    @Serializable(with = NameSerializer::class)
    override val sourceDevice: Name,
    @Serializable(with = NameSerializer::class)
    override val targetDevice: Name? = null,
    override val requestId: String? = null,
    override val correlationId: String? = null,
    override val hlcTimestamp: HlcTimestamp? = null,
) : DeviceMessage {
    override val messageType: String get() = DeviceMessageType.DeviceAttached

    override fun changeSource(block: (Name) -> Name): DeviceAttachedMessage =
        copy(sourceDevice = block(sourceDevice))

    override fun withHlcStamp(stamp: HlcTimestamp): DeviceAttachedMessage = copy(hlcTimestamp = stamp)
}

/**
 * Notifies that a device has been detached from a hub.
 */
@Serializable
@SerialName(DeviceMessageType.DeviceDetached)
public data class DeviceDetachedMessage(
    override val time: Instant,
    public val deviceName: Name,
    @Serializable(with = NameSerializer::class)
    override val sourceDevice: Name,
    @Serializable(with = NameSerializer::class)
    override val targetDevice: Name? = null,
    override val requestId: String? = null,
    override val correlationId: String? = null,
    override val hlcTimestamp: HlcTimestamp? = null,
) : DeviceMessage {
    override val messageType: String get() = DeviceMessageType.DeviceDetached

    override fun changeSource(block: (Name) -> Name): DeviceDetachedMessage =
        copy(sourceDevice = block(sourceDevice))

    override fun withHlcStamp(stamp: HlcTimestamp): DeviceDetachedMessage = copy(hlcTimestamp = stamp)
}

/**
 * Client-initiated request to read a property. Counterpart to the device-emitted
 * [PropertyChangedMessage] notification. Answered with [PropertyReadResponse] or
 * [PropertyFaultMessage].
 */
@Serializable
@SerialName(DeviceMessageType.PropertyReadRequest)
public data class PropertyReadRequest(
    override val time: Instant,
    public val property: String,
    public val callerIdentity: String? = null,
    @Serializable(with = NameSerializer::class)
    override val sourceDevice: Name?,
    @Serializable(with = NameSerializer::class)
    override val targetDevice: Name?,
    override val requestId: String,
    override val correlationId: String? = null,
    override val hlcTimestamp: HlcTimestamp? = null,
) : RequestMessage {
    override val messageType: String get() = DeviceMessageType.PropertyReadRequest

    override fun changeSource(block: (Name) -> Name): PropertyReadRequest =
        copy(sourceDevice = sourceDevice?.let(block))

    override fun withHlcStamp(stamp: HlcTimestamp): PropertyReadRequest = copy(hlcTimestamp = stamp)
}

/**
 * Successful response to [PropertyReadRequest]. Distinct from [PropertyChangedMessage]:
 * this answers a specific outstanding [requestId], not a state change.
 */
@Serializable
@SerialName(DeviceMessageType.PropertyReadResponse)
public data class PropertyReadResponse(
    override val time: Instant,
    public val property: String,
    public val value: Meta,
    @Serializable(with = NameSerializer::class)
    override val sourceDevice: Name?,
    @Serializable(with = NameSerializer::class)
    override val targetDevice: Name?,
    override val requestId: String,
    override val correlationId: String? = null,
    override val hlcTimestamp: HlcTimestamp? = null,
) : ResponseMessage {
    override val messageType: String get() = DeviceMessageType.PropertyReadResponse

    override fun changeSource(block: (Name) -> Name): PropertyReadResponse =
        copy(sourceDevice = sourceDevice?.let(block))

    override fun withHlcStamp(stamp: HlcTimestamp): PropertyReadResponse = copy(hlcTimestamp = stamp)
}

/** Client-initiated request to write a mutable property. Ack is [PropertyWriteResponse]. */
@Serializable
@SerialName(DeviceMessageType.PropertyWriteRequest)
public data class PropertyWriteRequest(
    override val time: Instant,
    public val property: String,
    public val value: Meta,
    public val callerIdentity: String? = null,
    @Serializable(with = NameSerializer::class)
    override val sourceDevice: Name?,
    @Serializable(with = NameSerializer::class)
    override val targetDevice: Name?,
    override val requestId: String,
    override val correlationId: String? = null,
    override val hlcTimestamp: HlcTimestamp? = null,
) : RequestMessage {
    override val messageType: String get() = DeviceMessageType.PropertyWriteRequest

    override fun changeSource(block: (Name) -> Name): PropertyWriteRequest =
        copy(sourceDevice = sourceDevice?.let(block))

    override fun withHlcStamp(stamp: HlcTimestamp): PropertyWriteRequest = copy(hlcTimestamp = stamp)
}

/**
 * Ack for [PropertyWriteRequest]. [observedValue] is a non-binding hint; the canonical
 * post-write value still arrives as a [PropertyChangedMessage] notification.
 */
@Serializable
@SerialName(DeviceMessageType.PropertyWriteResponse)
public data class PropertyWriteResponse(
    override val time: Instant,
    public val property: String,
    public val observedValue: Meta? = null,
    @Serializable(with = NameSerializer::class)
    override val sourceDevice: Name?,
    @Serializable(with = NameSerializer::class)
    override val targetDevice: Name?,
    override val requestId: String,
    override val correlationId: String? = null,
    override val hlcTimestamp: HlcTimestamp? = null,
) : ResponseMessage {
    override val messageType: String get() = DeviceMessageType.PropertyWriteResponse

    override fun changeSource(block: (Name) -> Name): PropertyWriteResponse =
        copy(sourceDevice = sourceDevice?.let(block))

    override fun withHlcStamp(stamp: HlcTimestamp): PropertyWriteResponse = copy(hlcTimestamp = stamp)
}

/** Fault response to [PropertyReadRequest] / [PropertyWriteRequest]. Mirrors [ActionFaultMessage]. */
@Serializable
@SerialName(DeviceMessageType.PropertyFault)
public data class PropertyFaultMessage(
    override val time: Instant,
    public val property: String,
    public val fault: DeviceFault,
    @Serializable(with = NameSerializer::class)
    override val sourceDevice: Name,
    @Serializable(with = NameSerializer::class)
    override val targetDevice: Name? = null,
    override val requestId: String,
    override val correlationId: String? = null,
    override val hlcTimestamp: HlcTimestamp? = null,
) : ResponseMessage {
    override val messageType: String get() = DeviceMessageType.PropertyFault

    override fun changeSource(block: (Name) -> Name): PropertyFaultMessage =
        copy(sourceDevice = block(sourceDevice))

    override fun withHlcStamp(stamp: HlcTimestamp): PropertyFaultMessage = copy(hlcTimestamp = stamp)
}

/**
 * Requests execution of a named action on a target device.
 * Sent by a remote client or bridge; consumed by [IngressBridge] or similar handler.
 *
 * @property actionName The action to invoke on the target device.
 * @property argument Optional input argument for the action.
 * @property callerIdentity Identity of the caller for security/audit purposes.
 */
@Serializable
@SerialName(DeviceMessageType.ActionExecuteRequest)
public data class ActionRequestMessage(
    override val time: Instant,
    public val actionName: String,
    public val argument: Meta? = null,
    public val callerIdentity: String? = null,
    @Serializable(with = NameSerializer::class)
    override val sourceDevice: Name?,
    @Serializable(with = NameSerializer::class)
    override val targetDevice: Name?,
    override val requestId: String,
    override val correlationId: String? = null,
    override val hlcTimestamp: HlcTimestamp? = null,
) : RequestMessage {
    override val messageType: String get() = DeviceMessageType.ActionExecuteRequest

    override fun changeSource(block: (Name) -> Name): ActionRequestMessage =
        copy(sourceDevice = sourceDevice?.let(block))

    override fun withHlcStamp(stamp: HlcTimestamp): ActionRequestMessage = copy(hlcTimestamp = stamp)
}

/**
 * Positive response to an [ActionRequestMessage].
 *
 * @property result The action's return value, or `null` if the action has no output.
 */
@Serializable
@SerialName(DeviceMessageType.ActionExecuteResponse)
public data class ActionResponseMessage(
    override val time: Instant,
    public val result: Meta?,
    @Serializable(with = NameSerializer::class)
    override val sourceDevice: Name?,
    @Serializable(with = NameSerializer::class)
    override val targetDevice: Name?,
    override val requestId: String,
    override val correlationId: String? = null,
    override val hlcTimestamp: HlcTimestamp? = null,
) : ResponseMessage {
    override val messageType: String get() = DeviceMessageType.ActionExecuteResponse

    override fun changeSource(block: (Name) -> Name): ActionResponseMessage =
        copy(sourceDevice = sourceDevice?.let(block))

    override fun withHlcStamp(stamp: HlcTimestamp): ActionResponseMessage = copy(hlcTimestamp = stamp)
}

/**
 * Optional descriptor payload for [DeviceOnlineMessage]. Most consumers should resolve
 * [DeviceOnlineMessage.blueprintId] through a registry; this snapshot is a bootstrap hint
 * for peers that do not have the blueprint cached yet.
 */
@Serializable
public data class DeviceDescriptorSnapshot(
    public val properties: Collection<PropertyDescriptor> = emptyList(),
    public val actions: Collection<ActionDescriptor> = emptyList(),
)

/**
 * Declares a device online and optionally primes subscribers with a lightweight state
 * snapshot. Differs from [DeviceAttachedMessage], which is a hub-level topology event
 * without device capability/state payload.
 *
 * Transport/session identifiers are intentionally not part of this DTO; place them in
 * the surrounding message envelope headers. [initialValues] is only a cache warm-up hint
 * and does not replace subsequent [PropertyChangedMessage] updates.
 */
@Serializable
@SerialName(DeviceMessageType.DeviceOnline)
public data class DeviceOnlineMessage(
    override val time: Instant,
    public val blueprintId: BlueprintId,
    public val descriptorSnapshot: DeviceDescriptorSnapshot? = null,
    public val initialValues: Map<String, Meta> = emptyMap(),
    @Serializable(with = NameSerializer::class)
    override val sourceDevice: Name,
    @Serializable(with = NameSerializer::class)
    override val targetDevice: Name? = null,
    override val requestId: String? = null,
    override val correlationId: String? = null,
    override val hlcTimestamp: HlcTimestamp? = null,
) : DeviceMessage {
    override val messageType: String get() = DeviceMessageType.DeviceOnline

    override fun changeSource(block: (Name) -> Name): DeviceOnlineMessage =
        copy(sourceDevice = block(sourceDevice))

    override fun withHlcStamp(stamp: HlcTimestamp): DeviceOnlineMessage = copy(hlcTimestamp = stamp)
}

/**
 * Declares a device offline. Unlike [DeviceDetachedMessage] (always explicit), covers
 * involuntary unavailability such as transport drops, watchdog timeouts, and process
 * crashes. Consumers should mark any cached state for [sourceDevice] as stale.
 */
@Serializable
@SerialName(DeviceMessageType.DeviceOffline)
public data class DeviceOfflineMessage(
    override val time: Instant,
    public val cause: DeviceDepartureReason,
    @Serializable(with = NameSerializer::class)
    override val sourceDevice: Name,
    @Serializable(with = NameSerializer::class)
    override val targetDevice: Name? = null,
    override val requestId: String? = null,
    override val correlationId: String? = null,
    override val hlcTimestamp: HlcTimestamp? = null,
) : DeviceMessage {
    override val messageType: String get() = DeviceMessageType.DeviceOffline

    override fun changeSource(block: (Name) -> Name): DeviceOfflineMessage =
        copy(sourceDevice = block(sourceDevice))

    override fun withHlcStamp(stamp: HlcTimestamp): DeviceOfflineMessage = copy(hlcTimestamp = stamp)
}
