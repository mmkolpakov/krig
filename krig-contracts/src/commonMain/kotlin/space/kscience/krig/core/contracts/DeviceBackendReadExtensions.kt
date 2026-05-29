package space.kscience.krig.core.contracts

import space.kscience.krig.api.data.ObservedValue
import space.kscience.krig.api.descriptors.PropertyDescriptor
import space.kscience.krig.api.result.getOrThrow
import space.kscience.dataforge.meta.Meta

/**
 * Reads [property] through the observed-value path and throws on failure.
 *
 * Protocol backends that expose native timestamps or quality should implement
 * [DeviceBackend.readObserved].
 *
 * @throws space.kscience.krig.api.faults.OperationFaultException on read failure.
 */
context(device: DeviceEnvironment)
public suspend fun DeviceBackend.readWithResult(
    property: PropertyDescriptor,
): ObservedValue<Meta?> =
    this.readObserved(property).getOrThrow()
