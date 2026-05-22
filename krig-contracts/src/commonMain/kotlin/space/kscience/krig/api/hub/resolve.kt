@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package space.kscience.krig.api.hub

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import space.kscience.krig.core.contracts.Device
import space.kscience.krig.core.contracts.DeviceNode
import space.kscience.krig.core.contracts.asNode
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName

/**
 * Resolve a multi-token name through a topology tree.
 * Returns `null` if any segment is absent.
 */
public fun DeviceNode.resolveNode(name: Name): DeviceNode? {
    var node: DeviceNode = this
    for (token in name.tokens) {
        node = node.children[token.body.asName()] ?: return null
    }
    return node
}

/** Resolve a device at [name], or `null` if the path points to a folder node. */
public fun DeviceNode.resolveDevice(name: Name): Device? =
    resolveNode(name)?.device

/** Convenience overload for devices that also expose a [DeviceNode] view. */
public fun Device.resolve(name: Name): Device? =
    asNode().resolveDevice(name)

/**
 * Hot variant of [resolve] — re-emits only when the relevant segment along the path changes.
 * Upstream snapshot changes that don't affect the head segment are suppressed via
 * `distinctUntilChangedBy`, so downstream collectors don't recompute unnecessarily.
 */
public fun DeviceNode.resolveFlow(path: Name): Flow<DeviceNode?> {
    val tokens = path.tokens
    if (tokens.isEmpty()) return flowOf(this)
    val head = tokens.first().body.asName()
    val tail = Name(tokens.drop(1))
    return childrenFlow
        .distinctUntilChangedBy { it[head] }
        .flatMapLatest { snapshot ->
            val child = snapshot[head]
            child?.resolveFlow(tail) ?: flowOf(null)
        }
}

/** Device-only view of [resolveFlow]. */
public fun DeviceNode.resolveDeviceFlow(path: Name): Flow<Device?> =
    resolveFlow(path).map { node -> node?.device }
