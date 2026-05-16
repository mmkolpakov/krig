package space.kscience.krig.api.identifiers

/**
 * Factory for typed [ControlsPermission] variants. [Permission.id] strings are part of
 * the audit log and ACL surface; renaming a device or property is a breaking change.
 */
public object ControlsPermissions {
    public fun deviceRead(device: String, property: String): ControlsPermission =
        ControlsPermission.DeviceRead(device, property)

    public fun deviceWrite(device: String, property: String): ControlsPermission =
        ControlsPermission.DeviceWrite(device, property)

    public fun deviceExecute(device: String, action: String): ControlsPermission =
        ControlsPermission.DeviceExecute(device, action)

    public fun deviceSubscribe(device: String): ControlsPermission =
        ControlsPermission.DeviceSubscribe(device)
}
