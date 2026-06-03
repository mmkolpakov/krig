package space.kscience.krig.core.contracts

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import space.kscience.dataforge.names.Name
import space.kscience.krig.core.InternalKrigApi
import space.kscience.krig.core.capabilities.Capability
import space.kscience.krig.core.capabilities.CapabilityKey

/**
 * Runtime owner of local pipeline capabilities.
 *
 * Intended for SDK internals and decorators, not for the normal device contract surface.
 */
@InternalKrigApi
public interface CapabilityHost {
    public val installedCapabilities: Collection<Capability<*>>

    public val capabilityToggles: CapabilityToggles

    public fun registerCapability(capability: Capability<*>)

    public suspend fun installCapability(capability: Capability<*>) {
        registerCapability(capability)
        context(this@CapabilityHost) { capability.onAttach() }
    }

    @Suppress("UNCHECKED_CAST")
    public fun <C : Capability<*>> capability(key: CapabilityKey<C>): C? =
        installedCapabilities.firstOrNull { it.key == key || it.key.id == key.id } as? C
}

@InternalKrigApi
public class CapabilityRegistry {
    private val lock = SynchronizedObject()
    private val capabilities: MutableMap<Name, Capability<*>> = linkedMapOf()
    private var detached = false

    public val installedCapabilities: Collection<Capability<*>>
        get() = synchronized(lock) { capabilities.values.toList() }

    public fun registerCapability(capability: Capability<*>) {
        synchronized(lock) { capabilities[capability.key.id] = capability }
    }

    @Suppress("UNCHECKED_CAST")
    public fun <C : Capability<*>> capability(key: CapabilityKey<C>): C? =
        synchronized(lock) { capabilities[key.id] as? C }

    /**
     * Detaches every owned capability exactly once, in reverse registration order, on behalf of
     * [host]. Repeat calls are no-ops; per-capability failures are reported, not propagated.
     */
    public suspend fun detachOnce(host: CapabilityHost) {
        val claimed = synchronized(lock) { if (detached) false else { detached = true; true } }
        if (!claimed) return
        for (capability in installedCapabilities.toList().asReversed()) {
            ignoreCleanupFailureSuspending { context(host) { capability.onDetach() } }
        }
    }
}
