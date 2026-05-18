package space.kscience.krig.core.contracts

import space.kscience.krig.api.data.DataQuality
import space.kscience.krig.api.data.ObservedValue
import space.kscience.krig.api.descriptors.PropertyDescriptor
import space.kscience.krig.api.result.getOrThrow
import space.kscience.dataforge.meta.Meta
import kotlin.time.Clock
/**
 * Reads [property] and wraps the value into an [ObservedValue] timestamped before I/O.
 * Protocol backends with native timestamps or quality should override instead of using this helper.
 * @throws space.kscience.krig.api.faults.DeviceFaultException on read failure.
 */
context(device: DeviceEnvironment)
public suspend fun DeviceBackend.readWithResult(
    property: PropertyDescriptor,
    clock: Clock = Clock.System,
): ObservedValue<Meta> {
    val readAt = clock.now() // Timestamp BEFORE I/O — closest to physical measurement time
    val value = this.read(property).getOrThrow()
    return ObservedValue(
        value = value,
        time = readAt,
        quality = DataQuality.GOOD,
    )
}
