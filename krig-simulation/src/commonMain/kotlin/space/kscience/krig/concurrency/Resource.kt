package space.kscience.krig.concurrency

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.internal.SynchronizedObject
import kotlinx.coroutines.internal.synchronized
import kotlinx.coroutines.suspendCancellableCoroutine
import space.kscience.krig.core.InternalKrigApi

/** Capacity-bounded simulation resource. */
public class Resource(
    public val name: String,
    public val capacity: Int,
    public val preemption: ResourcePreemptionPolicy = ResourcePreemptionPolicies.None,
    private val eventSink: ResourceEventSink = ResourceEventSink.None,
) {
    init {
        require(capacity > 0) { "Resource '$name' capacity must be positive, got $capacity" }
    }

    @OptIn(InternalCoroutinesApi::class)
    private val lock = SynchronizedObject()

    // All mutable state below is accessed under [lock].
    private var usedUnits: Int = 0
    private var seqCounter: Long = 0L
    private var claimCounter: Long = 0L
    private val waiters: ArrayDeque<Waiter> = ArrayDeque()
    private val holders: MutableList<Holder> = mutableListOf()
    private val stateFlow = MutableStateFlow(ResourceState(capacity, 0, 0))

    /** Observable `(capacity, used, waiting)` snapshot. */
    public val state: StateFlow<ResourceState> = stateFlow.asStateFlow()

    private class Holder(
        val claim: ResourceClaim,
        val job: Job?,
    ) {
        var preemptedBy: ResourceClaim? = null
    }

    private data class Waiter(
        val claim: ResourceClaim,
        val seqno: Long,
        val continuation: CancellableContinuation<Unit>,
        val holder: Holder?,
    )

    private data class PreemptedHolder(
        val holder: Holder,
        val cause: ResourcePreemptionCause,
    )

    private data class ResourceActions(
        val granted: List<Waiter> = emptyList(),
        val preempted: List<PreemptedHolder> = emptyList(),
        val events: List<ResourceEvent> = emptyList(),
    )

    /**
     * Suspends until [amount] units are available, then claims them. Prefer [use].
     */
    @OptIn(InternalCoroutinesApi::class)
    public suspend fun seize(
        amount: Int = 1,
        priority: ResourcePriority = ResourcePriority.DEFAULT,
    ) {
        check(claim(amount, priority, holderJob = null) == null)
    }

    @OptIn(InternalCoroutinesApi::class)
    private suspend fun claim(
        amount: Int,
        priority: ResourcePriority,
        holderJob: Job?,
    ): Holder? {
        require(amount in 1..capacity) {
            "seize amount must be in 1..$capacity (resource '$name'), got $amount"
        }

        val claim = newClaim(amount, priority)
        val holder = holderJob?.let { Holder(claim, it) }

        val immediate = synchronized(lock) {
            if (usedUnits + amount <= capacity && waiters.isEmpty()) {
                grantLocked(claim, holder)
                ResourceActions(
                    events = listOf(
                        eventLocked(ResourceEventType.Requested, claim),
                        eventLocked(ResourceEventType.Granted, claim),
                    ),
                )
            } else {
                null
            }
        }

        if (immediate != null) {
            dispatch(immediate)
            return holder
        }

        suspendCancellableCoroutine { cont ->
            val actions = synchronized(lock) {
                if (usedUnits + amount <= capacity && waiters.isEmpty()) {
                    val waiter = Waiter(claim, -1, cont, holder)
                    grantLocked(waiter)
                    ResourceActions(
                        granted = listOf(waiter),
                        events = listOf(
                            eventLocked(ResourceEventType.Requested, claim),
                            eventLocked(ResourceEventType.Granted, claim),
                        ),
                    )
                } else {
                    val waiter = Waiter(claim, ++seqCounter, cont, holder)
                    cont.invokeOnCancellation {
                        synchronized(lock) {
                            removeWaiterLocked(waiter)
                        }
                    }
                    insertWaiterLocked(waiter)
                    drainWaitersLocked(
                        listOf(
                            eventLocked(ResourceEventType.Requested, claim),
                            eventLocked(ResourceEventType.Queued, claim),
                        ),
                    )
                }
            }
            dispatch(actions)
        }

        return holder
    }

    /**
     * Runs [block] holding [amount] units, releasing them afterwards even on exception.
     */
    public suspend fun <R> use(
        amount: Int = 1,
        priority: ResourcePriority = ResourcePriority.DEFAULT,
        block: suspend () -> R,
    ): R {
        val holder = claim(amount, priority, currentCoroutineContext()[Job])
            ?: error("Scoped Resource.use must create a holder")
        try {
            return block()
        } finally {
            release(holder)
        }
    }

    /** Releases [amount] units claimed by [seize]. */
    @OptIn(InternalCoroutinesApi::class)
    public fun release(amount: Int = 1) {
        val actions = synchronized(lock) {
            require(usedUnits >= amount) {
                "Cannot release $amount units of '$name' — only $usedUnits held"
            }
            usedUnits -= amount
            drainWaitersLocked()
        }
        dispatch(actions)
    }

    /** Preempts every waiter whose priority is strictly less than [priority]. */
    @OptIn(InternalCoroutinesApi::class)
    public fun preemptWaitersBelow(priority: ResourcePriority) {
        val victims = synchronized(lock) {
            val victims = waiters.filter { it.claim.priority < priority }
            victims.forEach(waiters::remove)
            publishState()
            victims.map { waiter -> waiter to eventLocked(ResourceEventType.Preempted, waiter.claim) }
        }
        victims.forEach { (waiter, event) ->
            eventSink.emit(event)
            waiter.continuation.cancel(
                ResourcePreemptedException(
                    ResourcePreemptionCause(
                        resourceName = name,
                        requestedPriority = priority,
                        holderPriority = waiter.claim.priority,
                        amount = waiter.claim.amount,
                    ),
                ),
            )
        }
    }

    /** Cooperatively cancels active holders whose priority is strictly less than [priority]. */
    @OptIn(InternalCoroutinesApi::class)
    public fun preemptHoldersBelow(priority: ResourcePriority) {
        val actions = synchronized(lock) {
            val syntheticRequest = ResourceClaim(++claimCounter, 0, priority)
            val victims = holders
                .filter { holder -> holder.preemptedBy == null && holder.claim.priority < priority }
                .map { holder ->
                    holder.preemptedBy = syntheticRequest
                    PreemptedHolder(holder, preemptionCause(syntheticRequest, holder.claim))
                }
            ResourceActions(
                preempted = victims,
                events = victims.map { eventLocked(ResourceEventType.Preempted, it.holder.claim) },
            )
        }
        dispatch(actions)
    }

    /** Preempts both queued waiters and active holders below [priority]. */
    public fun preemptBelow(priority: ResourcePriority) {
        preemptWaitersBelow(priority)
        preemptHoldersBelow(priority)
    }

    // --- locked helpers (caller holds [lock]) ------------------------------------

    @OptIn(InternalCoroutinesApi::class)
    private fun newClaim(amount: Int, priority: ResourcePriority): ResourceClaim = synchronized(lock) {
        ResourceClaim(++claimCounter, amount, priority)
    }

    private fun grantLocked(claim: ResourceClaim, holder: Holder?) {
        usedUnits += claim.amount
        if (holder != null) holders += holder
        publishState()
    }

    private fun grantLocked(waiter: Waiter) {
        grantLocked(waiter.claim, waiter.holder)
    }

    private fun insertWaiterLocked(waiter: Waiter) {
        val ix = waiters.indexOfFirst { existing ->
            existing.claim.priority < waiter.claim.priority ||
                (existing.claim.priority == waiter.claim.priority && existing.seqno > waiter.seqno)
        }
        if (ix < 0) waiters.addLast(waiter) else waiters.add(ix, waiter)
        publishState()
    }

    private fun removeWaiterLocked(waiter: Waiter) {
        if (waiters.remove(waiter)) publishState()
    }

    private fun drainWaitersLocked(initialEvents: List<ResourceEvent> = emptyList()): ResourceActions {
        val granted = mutableListOf<Waiter>()
        val preempted = mutableListOf<PreemptedHolder>()
        val emitted = initialEvents.toMutableList()

        while (waiters.isNotEmpty()) {
            val head = waiters.first()
            if (usedUnits + head.claim.amount <= capacity) {
                waiters.removeFirst()
                grantLocked(head)
                granted += head
                emitted += eventLocked(ResourceEventType.Granted, head.claim)
            } else {
                val victims = preemptionVictimsLocked(head)
                preempted += victims
                emitted += victims.map { eventLocked(ResourceEventType.Preempted, it.holder.claim) }
                break
            }
        }

        publishState()
        return ResourceActions(granted, preempted, emitted)
    }

    private fun preemptionVictimsLocked(waiter: Waiter): List<PreemptedHolder> {
        val activeHolders = holders.filter { it.preemptedBy == null }
        if (activeHolders.isEmpty()) return emptyList()

        val claims = activeHolders.map { it.claim }
        val selected = preemption
            .selectVictims(ResourcePreemptionContext(waiter.claim, capacity - usedUnits, claims))
            .map { it.id }
            .toSet()
        if (selected.isEmpty()) return emptyList()

        return activeHolders
            .filter { holder -> holder.claim.id in selected }
            .map { holder ->
                holder.preemptedBy = waiter.claim
                PreemptedHolder(holder, preemptionCause(waiter.claim, holder.claim))
            }
    }

    private fun preemptionCause(request: ResourceClaim, holder: ResourceClaim): ResourcePreemptionCause =
        ResourcePreemptionCause(
            resourceName = name,
            requestedPriority = request.priority,
            holderPriority = holder.priority,
            amount = holder.amount,
        )

    private fun eventLocked(type: ResourceEventType, claim: ResourceClaim): ResourceEvent =
        ResourceEvent(
            type = type,
            resourceName = name,
            claim = claim,
            capacity = capacity,
            used = usedUnits,
            waiting = waiters.size,
        )

    private fun publishState() {
        stateFlow.value = ResourceState(capacity, usedUnits, waiters.size)
    }

    /** Internal inspection for tests. */
    @OptIn(InternalCoroutinesApi::class)
    @InternalKrigApi
    public fun currentWaiterCount(): Int = synchronized(lock) { waiters.size }

    @OptIn(InternalCoroutinesApi::class)
    private fun rollbackGranted(waiter: Waiter): ResourceActions = synchronized(lock) {
        if (usedUnits < waiter.claim.amount) return@synchronized ResourceActions()
        if (waiter.holder != null) holders.remove(waiter.holder)
        usedUnits -= waiter.claim.amount
        drainWaitersLocked(listOf(eventLocked(ResourceEventType.Released, waiter.claim)))
    }

    @OptIn(InternalCoroutinesApi::class)
    private fun release(holder: Holder) {
        val actions = synchronized(lock) {
            check(holders.remove(holder)) {
                "Cannot release a resource holder that is not active for '$name'"
            }
            check(usedUnits >= holder.claim.amount) {
                "Cannot release ${holder.claim.amount} units of '$name' — only $usedUnits held"
            }
            usedUnits -= holder.claim.amount
            holder.preemptedBy = null
            drainWaitersLocked(listOf(eventLocked(ResourceEventType.Released, holder.claim)))
        }
        dispatch(actions)
    }

    private fun dispatch(actions: ResourceActions) {
        actions.events.forEach(eventSink::emit)
        resumeGranted(actions.granted)
        actions.preempted.forEach { preempted ->
            preempted.holder.job?.cancel(ResourcePreemptedException(preempted.cause))
        }
    }

    private fun resumeGranted(waiters: List<Waiter>) {
        waiters.forEach { waiter ->
            waiter.continuation.resume(Unit) { _, _, _ ->
                dispatch(rollbackGranted(waiter))
            }
        }
    }

}
