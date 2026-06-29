package space.kscience.krig.api.messages

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.serializer

/**
 * Stable polymorphic serial names for core [DeviceMessage] DTOs.
 *
 * Keep this registry in sync with `@SerialName` annotations. It gives integrations a
 * single source for storage filters and protects the wire protocol from accidental
 * duplicate string literals.
 */
@Suppress("ConstPropertyName")
public object DeviceMessageType {
    public const val PropertyChanged: String = "property.changed"
    public const val Fault: String = "message.fault"
    public const val DeviceAttached: String = "hub.device-attached"
    public const val DeviceDetached: String = "hub.device-detached"
    public const val PropertyReadRequest: String = "property.read.request"
    public const val PropertyReadResponse: String = "property.read.response"
    public const val PropertyWriteRequest: String = "property.write.request"
    public const val PropertyWriteResponse: String = "property.write.response"
    public const val ActionExecuteRequest: String = "action.execute.request"
    public const val ActionExecuteResponse: String = "action.execute.response"
    public const val ActionExecuteCancel: String = "action.execute.cancel"
    public const val TaskStateChanged: String = "task.state.changed"
    public const val BatchReadRequest: String = "property.read-batch.request"
    public const val BatchReadResponse: String = "property.read-batch.response"
    public const val BatchWriteRequest: String = "property.write-batch.request"
    public const val BatchWriteResponse: String = "property.write-batch.response"
    public const val DeviceOnline: String = "device.online"
    public const val DeviceOffline: String = "device.offline"
    public const val TimeSeriesRow: String = "timeseries.row"

    public val all: Set<String> = setOf(
        PropertyChanged,
        Fault,
        DeviceAttached,
        DeviceDetached,
        PropertyReadRequest,
        PropertyReadResponse,
        PropertyWriteRequest,
        PropertyWriteResponse,
        ActionExecuteRequest,
        ActionExecuteResponse,
        ActionExecuteCancel,
        TaskStateChanged,
        BatchReadRequest,
        BatchReadResponse,
        BatchWriteRequest,
        BatchWriteResponse,
        DeviceOnline,
        DeviceOffline,
        TimeSeriesRow,
    )
}

/**
 * The stable wire type (`@SerialName`) of message type [T] — the type-level counterpart of
 * [DeviceMessage.messageType], reading the same single source of truth. Returns `null` when [T]
 * is polymorphic, abstract, or has no serializer; such types map to no single discriminator, so
 * callers (storage filters, routers) fall back to scanning instead of a parallel class→string map.
 */
@OptIn(ExperimentalSerializationApi::class)
public inline fun <reified T : DeviceMessage> messageTypeOrNull(): String? {
    val descriptor = try {
        serializer<T>().descriptor
    } catch (_: SerializationException) {
        return null
    }
    return if (descriptor.kind is PolymorphicKind) null else descriptor.serialName
}
