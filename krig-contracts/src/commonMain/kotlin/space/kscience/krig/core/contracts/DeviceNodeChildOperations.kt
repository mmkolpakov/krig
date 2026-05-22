@file:OptIn(space.kscience.krig.core.PerformancePitfall::class)

package space.kscience.krig.core.contracts

import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.Name

/**
 * Child-addressed operations on a topology [DeviceNode].
 */

/** Reads a property from a child device. Fails fast if the child is absent. */
public suspend fun DeviceNode.readChildProperty(deviceName: Name, propertyName: Name): Meta {
    val device = children[deviceName]?.device ?: error("Device '$deviceName' not found")
    return device.readProperty(propertyName)
}

/** Writes a property on a child device. */
public suspend fun DeviceNode.writeChildProperty(deviceName: Name, propertyName: Name, value: Meta) {
    val device = children[deviceName]?.device ?: error("Device '$deviceName' not found")
    device.writeProperty(propertyName, value)
}

/** Executes an action on a child device. */
public suspend fun DeviceNode.executeOnChild(
    deviceName: Name,
    actionName: Name,
    argument: Meta? = null,
): Meta? {
    val device = children[deviceName]?.device ?: error("Device '$deviceName' not found")
    return device.execute(actionName, argument)
}
