package space.kscience.krig.core.hook

import space.kscience.krig.core.contracts.Device
import space.kscience.dataforge.names.Name

/** Fired after a device is attached to a hub. */
public object DeviceAttached : Hook<suspend (Name, Device) -> Unit>

/** Fired before a device is detached from a hub. */
public object DeviceDetached : Hook<suspend (Name, Device) -> Unit>
