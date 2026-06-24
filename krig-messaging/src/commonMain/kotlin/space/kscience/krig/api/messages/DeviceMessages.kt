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
}

/**
 * Client-initiated request to read a property. Counterpart to the device-emitted
 * [PropertyChangedMessage] notification. Answered with [PropertyReadResponse] or a
 * [FaultMessage].
 *
 * @property callerIdentity Identity of the caller for security/audit purposes;
 *   carries `Principal.name` — roles are resolved against the local identity store on receipt.
 */
@Serializable
@SerialName(DeviceMessageType.PropertyReadRequest)
public data class PropertyReadRequest(
    override val time: Instant,
    public val property: Name,
    override val callerIdentity: String? = null,
    override val sourceDevice: Name?,
    override val targetDevice: Name?,
) : RequestMessage {
    override val messageType: String get() = DeviceMessageType.PropertyReadRequest
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
}

/**
 * Client-initiated request to write a mutable property. Ack is [PropertyWriteResponse].
 *
 * @property callerIdentity Identity of the caller for security/audit purposes;
 *   carries `Principal.name` — roles are resolved against the local identity store on receipt.
 */
@Serializable
@SerialName(DeviceMessageType.PropertyWriteRequest)
public data class PropertyWriteRequest(
    override val time: Instant,
    public val property: Name,
    public val value: Meta,
    override val callerIdentity: String? = null,
    override val sourceDevice: Name?,
    override val targetDevice: Name?,
) : RequestMessage {
    override val messageType: String get() = DeviceMessageType.PropertyWriteRequest
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
}

/**
 * Requests execution of a named action on a target device.
 * Sent by a remote client or a transport bridge that routes requests into the local runtime.
 *
 * @property actionName The action to invoke on the target device.
 * @property argument Optional input argument for the action.
 * @property callerIdentity Identity of the caller for security/audit purposes;
 *   carries `Principal.name` — roles are resolved against the local identity store on receipt.
 */
@Serializable
@SerialName(DeviceMessageType.ActionExecuteRequest)
public data class ActionRequestMessage(
    override val time: Instant,
    public val actionName: Name,
    public val argument: Meta? = null,
    override val callerIdentity: String? = null,
    override val sourceDevice: Name?,
    override val targetDevice: Name?,
) : RequestMessage {
    override val messageType: String get() = DeviceMessageType.ActionExecuteRequest
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
}

/**
 * Requests cancellation of an in-flight action. The action is identified by the request envelope's
 * correlation id (the same one carried by the original [ActionRequestMessage]); [actionName] is a
 * routing aid. The receiving runtime cancels the coroutine executing the action, surfacing as a
 * `CancellationException` to the running `execute`. There is no dedicated response: cancellation is
 * observed as the action's own terminal [FaultMessage]/[ActionResponseMessage].
 */
@Serializable
@SerialName(DeviceMessageType.ActionExecuteCancel)
public data class ActionCancelMessage(
    override val time: Instant,
    public val actionName: Name,
    override val callerIdentity: String? = null,
    override val sourceDevice: Name?,
    override val targetDevice: Name?,
) : RequestMessage {
    override val messageType: String get() = DeviceMessageType.ActionExecuteCancel
}

/** One property/value pair in a batch message; [quality] is meaningful for reads and ignored on writes. */
@Serializable
public data class BatchPropertyValue(
    public val property: Name,
    public val value: Meta,
    public val quality: DataQuality = DataQuality.GOOD,
)

/**
 * Client-initiated request to read several properties in one round-trip — the wire counterpart of
 * `Device.readBatchOutcome`. Answered with [BatchReadResponse]; per-property failures arrive as
 * [FaultMessage]s correlated to this request.
 */
@Serializable
@SerialName(DeviceMessageType.BatchReadRequest)
public data class BatchReadRequest(
    override val time: Instant,
    public val properties: List<Name>,
    override val callerIdentity: String? = null,
    override val sourceDevice: Name?,
    override val targetDevice: Name?,
) : RequestMessage {
    override val messageType: String get() = DeviceMessageType.BatchReadRequest
}

/** Successful values for a [BatchReadRequest]. Properties that failed are omitted and reported as faults. */
@Serializable
@SerialName(DeviceMessageType.BatchReadResponse)
public data class BatchReadResponse(
    override val time: Instant,
    public val values: List<BatchPropertyValue>,
    override val sourceDevice: Name?,
    override val targetDevice: Name?,
) : ResponseMessage {
    override val messageType: String get() = DeviceMessageType.BatchReadResponse
}

/**
 * Client-initiated request to write several mutable properties in one round-trip — the wire
 * counterpart of `Device.writeBatchOutcome`. Acknowledged by [BatchWriteResponse].
 */
@Serializable
@SerialName(DeviceMessageType.BatchWriteRequest)
public data class BatchWriteRequest(
    override val time: Instant,
    public val values: List<BatchPropertyValue>,
    override val callerIdentity: String? = null,
    override val sourceDevice: Name?,
    override val targetDevice: Name?,
) : RequestMessage {
    override val messageType: String get() = DeviceMessageType.BatchWriteRequest
}

/**
 * Ack for [BatchWriteRequest]. [observed] is a non-binding hint mirroring [PropertyWriteResponse];
 * the canonical post-write values still arrive as [PropertyChangedMessage] notifications.
 */
@Serializable
@SerialName(DeviceMessageType.BatchWriteResponse)
public data class BatchWriteResponse(
    override val time: Instant,
    public val observed: List<BatchPropertyValue> = emptyList(),
    override val sourceDevice: Name?,
    override val targetDevice: Name?,
) : ResponseMessage {
    override val messageType: String get() = DeviceMessageType.BatchWriteResponse
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
}

/**
 * One row of a dense double time-series chunk, lifted onto the event plane so a columnar capture can
 * join an event [space.kscience.krig.api.messages.DeviceMessageFrame] stream (e.g. via `Timeline.merge`)
 * for replay and digital-twin reconstruction. [series] names align positionally with [values]; optional
 * [qualities] (empty = all `GOOD`) align too.
 *
 * This is a replay/event-plane DTO, not the high-frequency write path: the dense column store
 * (`DenseDoubleTimeSeriesChunk`) stays the zero-allocation sink, and rows are lifted to messages only
 * for offline coordination.
 */
@Serializable
@SerialName(DeviceMessageType.TimeSeriesRow)
public data class TimeSeriesRowMessage(
    override val time: Instant,
    public val series: List<Name>,
    public val values: List<Double>,
    override val sourceDevice: Name,
    override val targetDevice: Name? = null,
    public val qualities: List<DataQuality> = emptyList(),
) : DeviceMessage {
    init {
        require(values.size == series.size) {
            "TimeSeriesRowMessage has ${values.size} values for ${series.size} series."
        }
        require(qualities.isEmpty() || qualities.size == series.size) {
            "TimeSeriesRowMessage qualities must be empty or match ${series.size} series, got ${qualities.size}."
        }
    }

    override val messageType: String get() = DeviceMessageType.TimeSeriesRow
}
