package space.kscience.krig.api.messages

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import space.kscience.krig.api.data.DataQuality
import space.kscience.krig.api.descriptors.ActionDescriptor
import space.kscience.krig.api.descriptors.PropertyDescriptor
import space.kscience.krig.api.faults.OperationFault
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName
import kotlin.time.Instant

/**
 * Notifies that a property's value has changed.
 */
@Serializable
@SerialName(DeviceMessageType.PropertyChanged)
public data class PropertyChangedMessage(
    override val time: Instant,
    public val property: Name,
    public val value: Meta,
    override val sourceDevice: Name,
    override val targetDevice: Name? = null,
    public val quality: DataQuality = DataQuality.GOOD,
) : DeviceMessage {
    override val messageType: String get() = DeviceMessageType.PropertyChanged

    override fun changeSource(block: (Name) -> Name): PropertyChangedMessage =
        copy(sourceDevice = block(sourceDevice))
}

/**
 * The single fault carrier on the control plane — replaces the former device/action/property fault
 * trio. The structured [fault] is the typed value-add; [scope] is loose DataForge [Meta] for optional
 * routing context (originating property/action, request correlation hint). Adapt a raw throwable with
 * `Throwable.toOperationFault`.
 *
 * Request/response correlation travels on the envelope ([MessageContext]), so a fault does not need a
 * dedicated response subtype; build scope with [FaultScope] keys when the producer knows the origin.
 */
@Serializable
@SerialName(DeviceMessageType.Fault)
public data class FaultMessage(
    override val time: Instant,
    public val fault: OperationFault,
    override val sourceDevice: Name,
    override val targetDevice: Name? = null,
    public val scope: Meta = Meta.EMPTY,
) : DeviceMessage {
    override val messageType: String get() = DeviceMessageType.Fault

    override fun changeSource(block: (Name) -> Name): FaultMessage =
        copy(sourceDevice = block(sourceDevice))
}

/** Conventional [FaultMessage.scope] keys for fault origin context. */
public object FaultScope {
    public val PROPERTY: Name = "property".asName()
    public val ACTION: Name = "action".asName()
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
    public val manifestId: Name,
    override val sourceDevice: Name,
    override val targetDevice: Name? = null,
) : DeviceMessage {
    override val messageType: String get() = DeviceMessageType.DeviceAttached

    override fun changeSource(block: (Name) -> Name): DeviceAttachedMessage =
        copy(sourceDevice = block(sourceDevice))
}

/**
 * Notifies that a device has been detached from a hub.
 */
@Serializable
@SerialName(DeviceMessageType.DeviceDetached)
public data class DeviceDetachedMessage(
    override val time: Instant,
    public val deviceName: Name,
    override val sourceDevice: Name,
    override val targetDevice: Name? = null,
) : DeviceMessage {
    override val messageType: String get() = DeviceMessageType.DeviceDetached

    override fun changeSource(block: (Name) -> Name): DeviceDetachedMessage =
        copy(sourceDevice = block(sourceDevice))
}

/**
 * Client-initiated request to read a property. Counterpart to the device-emitted
 * [PropertyChangedMessage] notification. Answered with [PropertyReadResponse] or a
 * [FaultMessage].
 */
@Serializable
@SerialName(DeviceMessageType.PropertyReadRequest)
public data class PropertyReadRequest(
    override val time: Instant,
    public val property: Name,
    public val callerIdentity: String? = null,
    override val sourceDevice: Name?,
    override val targetDevice: Name?,
) : RequestMessage {
    override val messageType: String get() = DeviceMessageType.PropertyReadRequest

    override fun changeSource(block: (Name) -> Name): PropertyReadRequest =
        copy(sourceDevice = sourceDevice?.let(block))
}

/**
 * Successful response to [PropertyReadRequest]. Distinct from [PropertyChangedMessage]:
 * this answers a specific outstanding request envelope, not a state change.
 */
@Serializable
@SerialName(DeviceMessageType.PropertyReadResponse)
public data class PropertyReadResponse(
    override val time: Instant,
    public val property: Name,
    public val value: Meta,
    override val sourceDevice: Name?,
    override val targetDevice: Name?,
    public val quality: DataQuality = DataQuality.GOOD,
) : ResponseMessage {
    override val messageType: String get() = DeviceMessageType.PropertyReadResponse

    override fun changeSource(block: (Name) -> Name): PropertyReadResponse =
        copy(sourceDevice = sourceDevice?.let(block))
}

/** Client-initiated request to write a mutable property. Ack is [PropertyWriteResponse]. */
@Serializable
@SerialName(DeviceMessageType.PropertyWriteRequest)
public data class PropertyWriteRequest(
    override val time: Instant,
    public val property: Name,
    public val value: Meta,
    public val callerIdentity: String? = null,
    override val sourceDevice: Name?,
    override val targetDevice: Name?,
) : RequestMessage {
    override val messageType: String get() = DeviceMessageType.PropertyWriteRequest

    override fun changeSource(block: (Name) -> Name): PropertyWriteRequest =
        copy(sourceDevice = sourceDevice?.let(block))
}

/**
 * Ack for [PropertyWriteRequest]. [observedValue] is a non-binding hint; the canonical
 * post-write value still arrives as a [PropertyChangedMessage] notification.
 */
@Serializable
@SerialName(DeviceMessageType.PropertyWriteResponse)
public data class PropertyWriteResponse(
    override val time: Instant,
    public val property: Name,
    public val observedValue: Meta? = null,
    override val sourceDevice: Name?,
    override val targetDevice: Name?,
    public val observedQuality: DataQuality? = null,
) : ResponseMessage {
    override val messageType: String get() = DeviceMessageType.PropertyWriteResponse

    override fun changeSource(block: (Name) -> Name): PropertyWriteResponse =
        copy(sourceDevice = sourceDevice?.let(block))
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
    override val sourceDevice: Name?,
    override val targetDevice: Name?,
) : RequestMessage {
    override val messageType: String get() = DeviceMessageType.ActionExecuteRequest

    override fun changeSource(block: (Name) -> Name): ActionRequestMessage =
        copy(sourceDevice = sourceDevice?.let(block))
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
    override val sourceDevice: Name?,
    override val targetDevice: Name?,
) : ResponseMessage {
    override val messageType: String get() = DeviceMessageType.ActionExecuteResponse

    override fun changeSource(block: (Name) -> Name): ActionResponseMessage =
        copy(sourceDevice = sourceDevice?.let(block))
}

/**
 * Optional descriptor payload for [DeviceOnlineMessage]. Most consumers should resolve
 * [DeviceOnlineMessage.manifestId] through a registry; this snapshot is a bootstrap hint
 * for peers that do not have the Manifest cached yet.
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
    public val manifestId: Name,
    public val descriptorSnapshot: DeviceDescriptorSnapshot? = null,
    public val initialValues: Map<String, Meta> = emptyMap(),
    override val sourceDevice: Name,
    override val targetDevice: Name? = null,
) : DeviceMessage {
    override val messageType: String get() = DeviceMessageType.DeviceOnline

    override fun changeSource(block: (Name) -> Name): DeviceOnlineMessage =
        copy(sourceDevice = block(sourceDevice))
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
    override val sourceDevice: Name,
    override val targetDevice: Name? = null,
) : DeviceMessage {
    override val messageType: String get() = DeviceMessageType.DeviceOffline

    override fun changeSource(block: (Name) -> Name): DeviceOfflineMessage =
        copy(sourceDevice = block(sourceDevice))
}
