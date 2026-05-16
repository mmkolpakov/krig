@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package space.kscience.krig.api.hub

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import space.kscience.krig.core.contracts.Device
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName

/**
 * Resolve a multi-token name through the device tree.
 * `hub.resolve("motor.x.encoder")` → `children["motor"].children["x"].children["encoder"]`.
 * Returns `null` if any segment is absent.
 */
public fun Device.resolve(name: Name): Device? {
    var node: Device = this
    for (token in name.tokens) {
        node = node.children[token.body.asName()] ?: return null
    }
    return node
}

/**
 * Hot variant of [resolve] — re-emits only when the relevant segment along the path changes.
 * Upstream snapshot changes that don't affect the head segment are suppressed via
 * `distinctUntilChangedBy`, so downstream collectors don't recompute unnecessarily.
 */
public fun Device.resolveFlow(path: Name): Flow<Device?> {
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
