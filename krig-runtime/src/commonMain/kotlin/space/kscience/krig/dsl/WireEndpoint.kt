package space.kscience.krig.dsl

/**
 * Typed endpoint for a cross-device property wiring in [DeviceGroupBuilder].
 * @property device Device name in the group.
 * @property property Property name on the device.
 */
public data class WireEndpoint(val device: String, val property: String)
