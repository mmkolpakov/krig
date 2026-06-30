@file:OptIn(
    space.kscience.krig.core.InternalKrigApi::class,
    space.kscience.krig.core.UnstableKrigForSubclassing::class,
)

package space.kscience.krig.core.runtime

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName
import space.kscience.krig.api.hub.DeviceHub
import space.kscience.krig.api.hub.HubConflictException
import space.kscience.krig.api.hub.HubEvent
import space.kscience.krig.api.messages.DeviceDepartureReason
import space.kscience.krig.core.contracts.Device
import space.kscience.krig.core.contracts.DeviceNode
import space.kscience.krig.core.contracts.asNodeMap
import space.kscience.krig.core.contracts.closeDeviceBounded
import space.kscience.krig.core.contracts.ignoreNonCancellationFailure
import space.kscience.krig.core.contracts.ignoreNonCancellationFailureSuspending
import space.kscience.krig.core.hook.DeviceAttached
import space.kscience.krig.core.hook.DeviceDetached
import space.kscience.krig.core.hook.HookRegistry

/** Thrown by mutation methods of a [DeviceHub] after [AutoCloseable.close] has been invoked. */
public class HubClosedException(hubName: String) :
    IllegalStateException("Hub '$hubName' is closed; no more attach/replace/detach allowed")

/**
 * [DeviceGroup] whose devices can be attached, replaced, and detached at runtime.
 * Topology is a single immutable state guarded by a short KMP-safe monitor.
 *
 * After [close], every mutation throws [HubClosedException]. Child `close()` calls and
 * suspend hooks from suspend mutations run outside the monitor, so user code never executes
 * under the topology lock.
 */
public open class MutableDeviceHub(
    name: Name,
    context: Context,
) : DeviceGroup(name, context, emptyMap()), DeviceHub {

    private val hubHooks: HookRegistry = HookRegistry.buffered()

    private val topologyLock = SynchronizedObject()
    private var topologyState: HubState = HubState.Active(emptyMap())

    /**
     * Names the hub *owns* (manages the lifecycle of): hub-produced children and children attached
     * with `owned = true` (the default). Owned children are closed by the hub on detach / close and
     * released when the hub scope is cancelled (see [init]); children attached with `owned = false`
     * are shared/external — the hub never closes them. Guarded by [topologyLock].
     */
    private val ownedChildren: MutableSet<Name> = mutableSetOf()
    private val mutableDevicesFlow: MutableStateFlow<Map<Name, Device>> = MutableStateFlow(emptyMap())
    private val mutableChildrenFlow: MutableStateFlow<Map<Name, DeviceNode>> = MutableStateFlow(emptyMap())
    private val mutableHubEvents: MutableSharedFlow<HubEvent> =
        MutableSharedFlow(
            replay = 0,
            extraBufferCapacity = HUB_EVENT_BUFFER_CAPACITY,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    override val device: Device get() = this

    override val devices: Map<Name, Device>
        get() = synchronized(topologyLock) {
            (topologyState as? HubState.Active)?.children.orEmpty()
        }

    override val devicesFlow: StateFlow<Map<Name, Device>> = mutableDevicesFlow.asStateFlow()

    // O(1), allocation-free: the node map is materialised once per topology change in publishChildren
    // and cached in mutableChildrenFlow, instead of rebuilding it (asNodeMap) on every children access.
    override val children: Map<Name, DeviceNode>
        get() = mutableChildrenFlow.value

    override val childrenFlow: StateFlow<Map<Name, DeviceNode>> = mutableChildrenFlow.asStateFlow()

    /**
     * Hot best-effort topology notifications for UI and local observers.
     *
     * [devicesFlow] / [childrenFlow] remain the source of truth. Persist topology
     * transitions through a control-plane message or an event journal when the consumer
     * needs audit-grade durability.
     */
    override val hubEvents: Flow<HubEvent> = mutableHubEvents.asSharedFlow()

    init {
        // Structured-concurrency safety: if the hub scope is cancelled directly (parent context
        // cancelled without close()), release owned children so they cannot outlive the hub. Shared
        // (owned = false) children are left to their own owners. Idempotent with the explicit close().
        deviceScope.coroutineContext[Job]?.invokeOnCompletion {
            val owned = synchronized(topologyLock) {
                (topologyState as? HubState.Active)?.children?.filterKeys { it in ownedChildren }.orEmpty()
            }
            for (child in owned.values) ignoreNonCancellationFailure { child.close() }
        }
    }

    override suspend fun attach(name: Name, device: Device) {
        attachAll(mapOf(name to device), owned = true)
    }

    override suspend fun attachAll(devices: Map<Name, Device>) {
        attachAll(devices, owned = true)
    }

    /**
     * Attaches [device] under [name]. With `owned = true` (the default for [attach]) the hub manages
     * its lifecycle (closes it on detach/close); with `owned = false` the device is shared/external
     * and the hub never closes it.
     */
    public fun attach(name: Name, device: Device, owned: Boolean) {
        attachAll(mapOf(name to device), owned)
    }

    /** Bulk [attach] with explicit ownership; see [attach]. */
    public fun attachAll(devices: Map<Name, Device>, owned: Boolean) {
        if (devices.isEmpty()) return
        val events = synchronized(topologyLock) {
            val active = activeState()
            val conflicting = devices.keys.intersect(active.children.keys)
            if (conflicting.isNotEmpty()) {
                throw HubConflictException(conflicting.first(), "already attached; use replace() for overwrite")
            }
            val nextChildren = active.children + devices
            topologyState = HubState.Active(nextChildren)
            if (owned) ownedChildren += devices.keys else ownedChildren -= devices.keys
            publishChildren(nextChildren)
            val now = clock.now()
            devices.map { (childName, child) -> HubEvent.Attached(childName, now, child::class.simpleName.orEmpty()) }
        }
        events.forEach(::emitTopologyEvent)
        for ((childName, child) in devices) dispatchAttachedHooks(childName, child)
    }

    override suspend fun replace(name: Name, device: Device, closePrevious: Boolean): Device? {
        val (previous, event) = synchronized(topologyLock) {
            val active = activeState()
            val prev = active.children[name]
            val nextChildren = active.children + (name to device)
            topologyState = HubState.Active(nextChildren)
            ownedChildren += name
            publishChildren(nextChildren)
            val event =
                HubEvent.Replaced(
                    name = name,
                    time = clock.now(),
                    previousType = prev?.let { it::class.simpleName.orEmpty() } ?: "",
                    newType = device::class.simpleName.orEmpty(),
                )
            prev to event
        }
        emitTopologyEvent(event)
        dispatchAttachedHooks(name, device)
        if (closePrevious && previous != null) {
            closeDeviceBounded(previous) { previous.shutdown() }
        }
        return previous
    }

    override suspend fun detach(name: Name, reason: DeviceDepartureReason): Device? {
        val detached = detachInternal(name, reason) ?: return null
        if (detached.owned) closeDeviceBounded(detached.device) { detached.device.shutdown() }
        return detached.device
    }

    override suspend fun release(name: Name, reason: DeviceDepartureReason): Device? =
        detachInternal(name, reason)?.device

    override suspend fun decommission(name: Name, reason: DeviceDepartureReason): Device? {
        val detached = detachInternal(name, reason) ?: return null
        closeDeviceBounded(detached.device) { detached.device.shutdown() }
        return detached.device
    }

    override suspend fun transfer(name: Name, target: DeviceHub, targetName: Name): Device? {
        if (target === this && targetName == name) return devices[name]

        val detached = detachInternal(name, DeviceDepartureReason.Transferred) ?: return null
        try {
            target.attach(targetName, detached.device)
        } catch (cause: Exception) {
            attachAll(mapOf(name to detached.device), owned = detached.owned)
            throw cause
        }
        return detached.device
    }

    private fun detachInternal(name: Name, reason: DeviceDepartureReason): DetachOutcome? {
        val detached = synchronized(topologyLock) {
            val active = topologyState as? HubState.Active ?: return@synchronized null
            val prev = active.children[name] ?: return@synchronized null
            val nextChildren = active.children - name
            val wasOwned = name in ownedChildren
            ownedChildren -= name
            topologyState = HubState.Active(nextChildren)
            publishChildren(nextChildren)
            DetachOutcome(prev, wasOwned, HubEvent.Detached(name, clock.now(), reason))
        } ?: return null
        emitTopologyEvent(detached.event)
        dispatchDetachedHooks(name, detached.device)
        return detached
    }

    override fun close() {
        val snapshot = closeTopology() ?: return
        snapshot.events.forEach(::emitTopologyEvent)
        for ((_, child) in snapshot.owned) {
            ignoreNonCancellationFailure { child.close() }
        }
        super<DeviceGroup>.close()
    }

    override suspend fun shutdown() {
        val snapshot = closeTopology() ?: return
        snapshot.events.forEach(::emitTopologyEvent)
        for ((_, child) in snapshot.owned) {
            closeDeviceBounded(child) { child.shutdown() }
        }
        super<DeviceGroup>.shutdown()
    }

    private fun activeState(): HubState.Active =
        topologyState as? HubState.Active ?: throw HubClosedException(name.toString())

    private fun closeTopology(): CloseSnapshot? = synchronized(topologyLock) {
        val active = topologyState as? HubState.Active ?: return@synchronized null
        topologyState = HubState.Closed
        val owned = active.children.filterKeys { it in ownedChildren }
        ownedChildren.clear()
        publishChildren(emptyMap())
        val now = clock.now()
        // Detached events for every child (topology truly changes for all); only owned ones are closed.
        CloseSnapshot(
            owned = owned,
            events = active.children.keys.map { HubEvent.Detached(it, now, DeviceDepartureReason.ParentClosed) },
        )
    }

    private fun publishChildren(children: Map<Name, Device>) {
        mutableDevicesFlow.value = children
        mutableChildrenFlow.value = children.asNodeMap()
    }

    // Best-effort hot notification (DROP_OLDEST): tryEmit never rejects, so the result is intentionally
    // ignored. Durable topology history belongs to devicesFlow/childrenFlow and the event journal.
    private fun emitTopologyEvent(event: HubEvent) {
        mutableHubEvents.tryEmit(event)
    }

    private fun dispatchAttachedHooks(childName: Name, child: Device) {
        hubHooks.handlersOf(DeviceAttached).forEach { handler ->
            deviceScope.launch(start = CoroutineStart.UNDISPATCHED) {
                ignoreNonCancellationFailureSuspending { handler(childName, child) }
            }
        }
    }

    private fun dispatchDetachedHooks(childName: Name, child: Device) {
        hubHooks.handlersOf(DeviceDetached).forEach { handler ->
            deviceScope.launch(start = CoroutineStart.UNDISPATCHED) {
                ignoreNonCancellationFailureSuspending { handler(childName, child) }
            }
        }
    }
}

private sealed interface HubState {
    data class Active(val children: Map<Name, Device>) : HubState
    data object Closed : HubState
}

/** Result of a [MutableDeviceHub.detach]: the removed device, whether the hub owned it, and the event. */
private data class DetachOutcome(val device: Device, val owned: Boolean, val event: HubEvent)

/** Snapshot taken when a hub closes: the owned children to release plus detach events for all children. */
private data class CloseSnapshot(val owned: Map<Name, Device>, val events: List<HubEvent>)

private const val HUB_EVENT_BUFFER_CAPACITY: Int = 1024

public fun deviceHub(name: Name, context: Context): MutableDeviceHub =
    MutableDeviceHub(name, context)

public fun deviceHub(name: String, context: Context): MutableDeviceHub =
    MutableDeviceHub(name.asName(), context)
