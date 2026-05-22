@file:OptIn(
    space.kscience.krig.core.InternalKrigApi::class,
    space.kscience.krig.core.UnstableKrigForSubclassing::class,
)

package space.kscience.krig.core.runtime

import kotlinx.coroutines.CoroutineStart
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
import space.kscience.krig.core.contracts.ignoreCleanupFailureSuspending
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

    override val hubHooks: HookRegistry = HookRegistry.buffered()

    private val topologyLock = SynchronizedObject()
    private var topologyState: HubState = HubState.Active(emptyMap())
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

    override val children: Map<Name, DeviceNode>
        get() = devices.asNodeMap()

    override val childrenFlow: StateFlow<Map<Name, DeviceNode>> = mutableChildrenFlow.asStateFlow()
    override val hubEvents: Flow<HubEvent> = mutableHubEvents.asSharedFlow()

    override suspend fun attach(name: Name, device: Device) {
        attachAll(mapOf(name to device))
    }

    override suspend fun attachAll(devices: Map<Name, Device>) {
        if (devices.isEmpty()) return
        val events = synchronized(topologyLock) {
            val active = activeState()
            val conflicting = devices.keys.intersect(active.children.keys)
            if (conflicting.isNotEmpty()) {
                throw HubConflictException(conflicting.first(), "already attached; use replace() for overwrite")
            }
            val nextChildren = active.children + devices
            topologyState = HubState.Active(nextChildren)
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
            publishChildren(nextChildren)
            val event =
                HubEvent.Replaced(
                    name = name,
                    time = clock.now(),
                    previousContractFqName = prev?.let { it::class.simpleName.orEmpty() } ?: "",
                    newContractFqName = device::class.simpleName.orEmpty(),
                )
            prev to event
        }
        emitTopologyEvent(event)
        dispatchAttachedHooks(name, device)
        if (closePrevious && previous != null) {
            ignoreCleanupFailureSuspending { previous.shutdown() }
        }
        return previous
    }

    override suspend fun detach(name: Name, reason: DeviceDepartureReason): Device? {
        val (victim, event) = synchronized(topologyLock) {
            val active = topologyState as? HubState.Active ?: return@synchronized null
            val prev = active.children[name] ?: return@synchronized null
            val nextChildren = active.children - name
            topologyState = HubState.Active(nextChildren)
            publishChildren(nextChildren)
            prev to HubEvent.Detached(name, clock.now(), reason)
        } ?: return null
        emitTopologyEvent(event)
        ignoreCleanupFailureSuspending { victim.shutdown() }
        dispatchDetachedHooks(name, victim)
        return victim
    }

    override fun close() {
        val (snapshot, events) = closeTopology() ?: return
        events.forEach(::emitTopologyEvent)
        for ((_, child) in snapshot) {
            ignoreNonCancellationFailure { child.close() }
        }
        super<DeviceGroup>.close()
    }

    override suspend fun shutdown() {
        val (snapshot, events) = closeTopology() ?: return
        events.forEach(::emitTopologyEvent)
        for ((_, child) in snapshot) {
            ignoreCleanupFailureSuspending { child.shutdown() }
        }
        super<DeviceGroup>.shutdown()
    }

    private fun activeState(): HubState.Active =
        topologyState as? HubState.Active ?: throw HubClosedException(name.toString())

    private fun closeTopology(): Pair<Map<Name, Device>, List<HubEvent>>? = synchronized(topologyLock) {
        val active = topologyState as? HubState.Active ?: return@synchronized null
        topologyState = HubState.Closed
        publishChildren(emptyMap())
        val now = clock.now()
        active.children to active.children.keys.map {
            HubEvent.Detached(it, now, DeviceDepartureReason.ParentClosed)
        }
    }

    private fun publishChildren(children: Map<Name, Device>) {
        mutableDevicesFlow.value = children
        mutableChildrenFlow.value = children.asNodeMap()
    }

    private fun emitTopologyEvent(event: HubEvent) {
        check(mutableHubEvents.tryEmit(event)) { "Hub event buffer rejected $event" }
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

private const val HUB_EVENT_BUFFER_CAPACITY: Int = 1024

public fun deviceHub(name: Name, context: Context): MutableDeviceHub =
    MutableDeviceHub(name, context)

public fun deviceHub(name: String, context: Context): MutableDeviceHub =
    MutableDeviceHub(name.asName(), context)
