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
import space.kscience.dataforge.names.plus
import space.kscience.krig.api.faults.GenericOperationFault
import space.kscience.krig.api.hub.resolve
import space.kscience.krig.api.result.OperationOutcome
import space.kscience.krig.api.result.fail
import space.kscience.krig.core.contracts.Device
import space.kscience.krig.core.contracts.DeviceNode
import space.kscience.krig.core.contracts.asNode
import space.kscience.krig.core.meta.DevicePropertyContract

/**
 * Recursively flattens this device topology into hierarchical [Name] paths. This is the canonical
 * topology projection: token bodies, indexes and escaping are preserved by composing existing
 * DataForge [Name] values rather than joining strings.
 *
 * Folder nodes that own no [device][DeviceNode.device] are skipped; every owning device — including
 * intermediate groups — is emitted.
 */
public fun DeviceNode.flattenDeviceTopology(): Map<Name, Device> {
    val result = LinkedHashMap<Name, Device>()
    fun walk(prefix: Name, node: DeviceNode) {
        for ((childName, child) in node.children) {
            val path = if (prefix == Name.EMPTY) childName else prefix + childName
            child.device?.let { result[path] = it }
            walk(path, child)
        }
    }
    walk(Name.EMPTY, this)
    return result
}

/**
 * Recursively flattens this device topology into the acquisition source-id convention: each
 * hierarchical topology path becomes one opaque single-token id via [toAcquisitionSourceId].
 * Prefer [flattenDeviceTopology] plus [AcquisitionSourceSpec.topologyPath] when a configuration must
 * preserve topology/path semantics explicitly.
 */
public fun Device.flattenDevices(): Map<Name, Device> =
    asNode().flattenDeviceTopology().mapKeys { (path, _) -> path.toAcquisitionSourceId() }

/**
 * Reactive counterpart of [flattenDevices]: a cold [Flow] that re-emits the flat `path → Device` map
 * whenever the topology changes anywhere in the subtree. It recurses over [DeviceNode.childrenFlow], so
 * a change deep in a nested hub propagates up without manual cache invalidation — the staleness that
 * makes a hand-rolled cache unsafe for dynamic topologies. Keys are hierarchical topology paths; call
 * [toAcquisitionSourceId] when an acquisition source id projection is needed.
 */
public fun Device.flatDevicesFlow(): Flow<Map<Name, Device>> = asNode().flatDevicesFlow(prefix = Name.EMPTY)

@OptIn(ExperimentalCoroutinesApi::class)
private fun DeviceNode.flatDevicesFlow(prefix: Name): Flow<Map<Name, Device>> =
    childrenFlow.flatMapLatest { children ->
        if (children.isEmpty()) {
            flowOf(emptyMap())
        } else {
            val childFlows = children.map { (childName, child) ->
                val path = if (prefix == Name.EMPTY) childName else prefix + childName
                child.flatDevicesFlow(path).map { nested ->
                    buildMap {
                        child.device?.let { put(path, it) }
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
 * present topology ([flattenDeviceTopology]). `flatDevicesIn(scope).value` is an O(1) lookup of the live flat
 * map — the intended path for repeated source resolution (acquisition sessions) without re-walking the
 * tree on every read.
 */
public fun Device.flatDevicesIn(scope: CoroutineScope): StateFlow<Map<Name, Device>> =
    flatDevicesFlow().stateIn(scope, SharingStarted.Eagerly, asNode().flattenDeviceTopology())

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
 * Typed read of a single leaf property addressed by [path]. Hierarchical topology paths are resolved
 * through [DeviceNode.flattenDeviceTopology] / [resolve]; single-token acquisition source ids are resolved
 * through [flattenDevices]. A missing path becomes an [OperationOutcome.Fail] rather than throwing,
 * so callers stay on the errors-as-values path.
 */
public suspend fun <T> Device.readAt(
    path: Name,
    spec: DevicePropertyContract<T>,
): OperationOutcome<T> {
    val device = asNode().flattenDeviceTopology()[path] ?: flattenDevices()[path] ?: resolve(path)
        ?: return fail(GenericOperationFault(message = "No device at path '$path'."))
    return device.readOutcome(spec)
}
