package space.kscience.krig.api.messages

/**
 * Stable polymorphic serial names for core [DeviceMessage] DTOs.
 *
 * Keep this registry in sync with `@SerialName` annotations. It gives integrations a
 * single source for storage filters and protects the wire protocol from accidental
 * duplicate string literals.
 */
public object DeviceMessageType {
    public const val PropertyChanged: String = "property.changed"
    public const val DeviceError: String = "message.error"
    public const val ActionFault: String = "action.fault"
    public const val DeviceAttached: String = "hub.device-attached"
    public const val DeviceDetached: String = "hub.device-detached"
    public const val PropertyReadRequest: String = "property.read.request"
    public const val PropertyReadResponse: String = "property.read.response"
    public const val PropertyWriteRequest: String = "property.write.request"
    public const val PropertyWriteResponse: String = "property.write.response"
    public const val PropertyFault: String = "property.fault"
    public const val ActionExecuteRequest: String = "action.execute.request"
    public const val ActionExecuteResponse: String = "action.execute.response"
    public const val DeviceOnline: String = "device.online"
    public const val DeviceOffline: String = "device.offline"

    public val all: Set<String> = setOf(
        PropertyChanged,
        DeviceError,
        ActionFault,
        DeviceAttached,
        DeviceDetached,
        PropertyReadRequest,
        PropertyReadResponse,
        PropertyWriteRequest,
        PropertyWriteResponse,
        PropertyFault,
        ActionExecuteRequest,
        ActionExecuteResponse,
        DeviceOnline,
        DeviceOffline,
    )
}
