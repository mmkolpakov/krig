package space.kscience.krig.assembly

import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName
import space.kscience.krig.api.faults.GenericOperationFault
import space.kscience.krig.api.hub.resolve
import space.kscience.krig.api.result.OperationOutcome
import space.kscience.krig.api.result.fail
import space.kscience.krig.core.contracts.Device
import space.kscience.krig.core.contracts.DeviceNode
import space.kscience.krig.core.contracts.asNode
import space.kscience.krig.core.meta.DevicePropertyContract

/**
 * Recursively flattens this device topology into a flat [Name]-addressable map keyed by dotted path
 * strings (`"plant.aux.booster"`). Keys are single-token names whose body is the joined path, which
 * is exactly the acquisition source-id convention (`source("plant.main")`), so the result drops
 * straight into [deviceTreeAcquisitionReader] without a hand-built `mapOf(... .asName())`. Folder
 * nodes that own no [device][DeviceNode.device] are skipped; every owning device — including
 * intermediate groups — is emitted.
 */
public fun Device.flattenDevices(): Map<Name, Device> {
    val result = LinkedHashMap<Name, Device>()
    fun walk(prefix: String?, node: DeviceNode) {
        for ((token, child) in node.children) {
            val path = if (prefix == null) token.toString() else "$prefix.$token"
            child.device?.let { result[path.asName()] = it }
            walk(path, child)
        }
    }
    walk(null, asNode())
    return result
}

/**
 * Acquisition source reader over this device topology: resolves a source by its dotted path through
 * [flattenDevices] and reads the tagged properties in one batch. Replaces the manual
 * `deviceTreeAcquisitionReader(mapOf(...))` wiring when devices already live in a `deviceGroup { }`.
 */
public fun Device.acquisitionReader(): AcquisitionSourceReader =
    deviceTreeAcquisitionReader(flattenDevices())

/**
 * Typed read of a single leaf property addressed by [path]. Uses the same addressing as
 * [flattenDevices] / [acquisitionReader] (single-token dotted path, the acquisition convention),
 * falling back to topology [resolve] when a genuine multi-token name is supplied. A missing path
 * becomes an [OperationOutcome.Fail] rather than throwing, so callers stay on the errors-as-values
 * path.
 */
public suspend fun <T> Device.readAt(
    path: Name,
    spec: DevicePropertyContract<T>,
): OperationOutcome<T> {
    val device = flattenDevices()[path] ?: resolve(path)
        ?: return fail(GenericOperationFault(message = "No device at path '$path'."))
    return device.readOutcome(spec)
}
