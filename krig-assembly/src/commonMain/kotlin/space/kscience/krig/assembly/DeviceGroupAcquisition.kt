package space.kscience.krig.assembly

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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
 * Reactive counterpart of [flattenDevices]: a cold [Flow] that re-emits the flat `path → Device` map
 * whenever the topology changes anywhere in the subtree. It recurses over [DeviceNode.childrenFlow], so
 * a change deep in a nested hub propagates up without manual cache invalidation — the staleness that
 * makes a hand-rolled cache of [flattenDevices] unsafe for dynamic topologies. Keys follow the same
 * single-token dotted-path convention as [flattenDevices].
 */
public fun Device.flatDevicesFlow(): Flow<Map<Name, Device>> = asNode().flatDevicesFlow(prefix = null)

@OptIn(ExperimentalCoroutinesApi::class)
private fun DeviceNode.flatDevicesFlow(prefix: String?): Flow<Map<Name, Device>> =
    childrenFlow.flatMapLatest { children ->
        if (children.isEmpty()) {
            flowOf(emptyMap())
        } else {
            val childFlows = children.map { (token, child) ->
                val path = if (prefix == null) token.toString() else "$prefix.$token"
                child.flatDevicesFlow(path).map { nested ->
                    buildMap {
                        child.device?.let { put(path.asName(), it) }
                        putAll(nested)
                    }
                }
            }
            combine(childFlows) { maps ->
                val flat = LinkedHashMap<Name, Device>()
                for (map in maps) flat.putAll(map)
                flat
            }
        }
    }

/**
 * Materialises [flatDevicesFlow] into a [StateFlow] kept current in [scope], seeded eagerly with the
 * present topology ([flattenDevices]). `flatDevicesIn(scope).value` is an O(1) lookup of the live flat
 * map — the intended path for repeated source resolution (acquisition sessions) without re-walking the
 * tree on every read.
 */
public fun Device.flatDevicesIn(scope: CoroutineScope): StateFlow<Map<Name, Device>> =
    flatDevicesFlow().stateIn(scope, SharingStarted.Eagerly, flattenDevices())

/**
 * Acquisition source reader over this device topology: resolves a source by its dotted path through
 * [flattenDevices] and reads the tagged properties in one batch. Replaces the manual
 * `deviceTreeAcquisitionReader(mapOf(...))` wiring when devices already live in a `deviceGroup { }`.
 *
 * Snapshots the topology once; for a hub whose children change at runtime, drive the reader from
 * [flatDevicesIn]`(scope).value` so source resolution tracks the live tree.
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
