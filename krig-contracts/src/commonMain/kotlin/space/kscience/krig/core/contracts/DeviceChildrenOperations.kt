@file:OptIn(space.kscience.krig.core.PerformancePitfall::class)

package space.kscience.krig.core.contracts

import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.Name

/**
 * Child-addressed operations on a [Device] that carries [Device.children].
 *
 * Fractal contract: every [Device] is potentially a container. Leaf devices return an empty
 * `children` map and these helpers fail fast with a clear message; hub-like devices surface the
 * same API without needing a separate `DeviceHub` type.
 */

/** Reads a property from a child device. Fails fast if the child is absent. */
public suspend fun Device.readChildProperty(deviceName: Name, propertyName: Name): Meta {
    val device = children[deviceName] ?: error("Device '$deviceName' not found under '${name}'")
    return device.readProperty(propertyName)
}

/** Writes a property on a child device. */
public suspend fun Device.writeChildProperty(deviceName: Name, propertyName: Name, value: Meta) {
    val device = children[deviceName] ?: error("Device '$deviceName' not found under '${name}'")
    device.writeProperty(propertyName, value)
}

/** Executes an action on a child device. */
public suspend fun Device.executeOnChild(
    deviceName: Name,
    actionName: Name,
    argument: Meta? = null,
): Meta? {
    val device = children[deviceName] ?: error("Device '$deviceName' not found under '${name}'")
    return device.execute(actionName, argument)
}
