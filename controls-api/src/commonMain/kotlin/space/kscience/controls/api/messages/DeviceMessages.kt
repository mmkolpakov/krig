package space.kscience.controls.api.messages

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import space.kscience.controls.api.addressing.Address
import space.kscience.controls.api.descriptors.ActionDescriptor
import space.kscience.controls.api.descriptors.PropertyDescriptor
import space.kscience.controls.api.faults.DeviceFault
import space.kscience.controls.api.faults.SerializableDeviceFailure
import space.kscience.controls.api.identifiers.BlueprintId
import space.kscience.controls.api.identifiers.CorrelationId
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.Name
import kotlin.time.Instant

/**
 * Notifies that a property's value has changed. This is a notification, not a response.
 *
 * @param property The name of the property that changed.
 * @param value The new value of the property.
 * @param sourceDevice The name of the device owning the property. Mandatory.
 */
@Serializable
@SerialName("property.changed")
public data class PropertyChangedMessage(
    override val time: Instant,
    public val property: String,
    public val value: Meta,
    override val sourceDevice: Address,
    override val targetDevice: Address? = null,
    override val requestId: String? = null,
    override val correlationId: CorrelationId? = null,
) : DeviceMessage {
    override fun changeSource(block: (Name) -> Name): PropertyChangedMessage =
        copy(sourceDevice = sourceDevice.copy(device = block(sourceDevice.device)))
}

/**
 * A message providing the full description of a device, including its properties and actions.
 * Typically sent in response to a request.
 *
 * @param description The general metadata of the device.
 * @param properties A collection of descriptors for all supported properties.
 * @param actions A collection of descriptors for all supported actions.
 * @param sourceDevice The name of the described device. Mandatory.
 */
@Serializable
@SerialName("description")
public data class DescriptionMessage(
    override val time: Instant,
    val description: Meta,
    val properties: Collection<PropertyDescriptor>,
    val actions: Collection<ActionDescriptor>,
    override val sourceDevice: Address,
    override val targetDevice: Address? = null,
    override val requestId: String,
    override val correlationId: CorrelationId? = null,
) : ResponseMessage {
    override fun changeSource(block: (Name) -> Name): DescriptionMessage =
        copy(sourceDevice = sourceDevice.copy(device = block(sourceDevice.device)))
}

/**
 * A message indicating that an error occurred.
 *
 * @param failure A structured, serializable representation of the error.
 * @param sourceDevice The name of the device where the error occurred. Mandatory.
 */
@Serializable
@SerialName("error")
public data class DeviceErrorMessage(
    override val time: Instant,
    public val failure: SerializableDeviceFailure,
    override val sourceDevice: Address,
    override val targetDevice: Address? = null,
    override val requestId: String?,
    override val correlationId: CorrelationId? = null,
) : DeviceMessage {
    override fun changeSource(block: (Name) -> Name): DeviceErrorMessage =
        copy(sourceDevice = sourceDevice.copy(device = block(sourceDevice.device)))
}

@Serializable
@SerialName("action.fault")
public data class ActionFaultMessage(
    override val time: Instant,
    public val fault: DeviceFault,
    override val sourceDevice: Address,
    override val targetDevice: Address? = null,
    override val requestId: String,
    override val correlationId: CorrelationId? = null,
) : ResponseMessage {
    override fun changeSource(block: (Name) -> Name): ActionFaultMessage =
        copy(sourceDevice = sourceDevice.copy(device = block(sourceDevice.device)))
}

/**
 * Notifies that the logical state of a boolean predicate property has changed.
 * This specialized message allows clients to react to changes in conditions (e.g., "ready", "in_range")
 * without needing to subscribe to and evaluate the underlying numeric properties themselves.
 *
 * @param predicateName The name of the predicate property that changed.
 * @param newState The new boolean state of the predicate.
 * @param sourceDevice The address of the device owning the predicate. Mandatory.
 */
@Serializable
@SerialName("predicate.changed")
public data class PredicateChangedMessage(
    override val time: Instant,
    public val predicateName: String,
    public val newState: Boolean,
    override val sourceDevice: Address,
    override val targetDevice: Address? = null,
    override val requestId: String? = null,
    override val correlationId: CorrelationId? = null,
) : DeviceMessage {
    override fun changeSource(block: (Name) -> Name): PredicateChangedMessage =
        copy(sourceDevice = sourceDevice.copy(device = block(sourceDevice.device)))
}

/**
 * A notification that a new device has been attached to a hub.
 * This allows clients to dynamically discover and update the device topology without polling.
 *
 * @param deviceName The local, hierarchical name of the newly attached device.
 * @param blueprintId The identifier of the blueprint used to create the device.
 * @param sourceDevice The address of the hub that originated this message. Mandatory.
 */
@Serializable
@SerialName("hub.deviceAttached")
public data class DeviceAttachedMessage(
    override val time: Instant,
    public val deviceName: Name,
    public val blueprintId: BlueprintId,
    override val sourceDevice: Address,
    override val targetDevice: Address? = null,
    override val requestId: String? = null,
    override val correlationId: CorrelationId? = null,
) : DeviceMessage {
    override fun changeSource(block: (Name) -> Name): DeviceAttachedMessage =
        copy(sourceDevice = sourceDevice.copy(device = block(sourceDevice.device)))
}

/**
 * A notification that a device has been detached from a hub.
 * This allows clients to remove the device from their representation of the hub's topology.
 *
 * @param deviceName The local, hierarchical name of the detached device.
 * @param sourceDevice The address of the hub that originated this message. Mandatory.
 */
@Serializable
@SerialName("hub.deviceDetached")
public data class DeviceDetachedMessage(
    override val time: Instant,
    public val deviceName: Name,
    override val sourceDevice: Address,
    override val targetDevice: Address? = null,
    override val requestId: String? = null,
    override val correlationId: CorrelationId? = null,
) : DeviceMessage {
    override fun changeSource(block: (Name) -> Name): DeviceDetachedMessage =
        copy(sourceDevice = sourceDevice.copy(device = block(sourceDevice.device)))
}

/**
 * A request to retrieve a specific piece of binary content.
 *
 * @param contentId The unique identifier of the content to retrieve.
 * @param sourceDevice The address of the requester.
 * @param targetDevice The address of the device holding the data. Mandatory.
 */
@Serializable
@SerialName("binary.request")
public data class BinaryDataRequest(
    override val time: Instant,
    val contentId: String,
    override val sourceDevice: Address?,
    override val targetDevice: Address,
    override val requestId: String,
    override val correlationId: CorrelationId? = null,
) : RequestMessage {
    override fun changeSource(block: (Name) -> Name): BinaryDataRequest =
        copy(sourceDevice = sourceDevice?.copy(device = block(sourceDevice.device)))
}

/**
 * Notifies listeners that a new binary content is available for retrieval from this device.
 * The actual data transfer must be done via a separate mechanism like [PeerConnection].
 *
 * @param contentId A unique identifier for this specific piece of binary content.
 * @param contentMeta Metadata describing the binary content (e.g., size, format).
 * @param sourceDevice The address of the device providing the binary data. Mandatory.
 */
@Serializable
@SerialName("binary.ready")
public data class BinaryReadyNotification(
    override val time: Instant,
    val contentId: String,
    val contentMeta: Meta,
    override val sourceDevice: Address,
    override val targetDevice: Address? = null,
    override val requestId: String? = null,
    override val correlationId: CorrelationId? = null,
) : DeviceMessage {
    override fun changeSource(block: (Name) -> Name): BinaryReadyNotification =
        copy(sourceDevice = sourceDevice.copy(device = block(sourceDevice.device)))
}