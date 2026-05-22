package space.kscience.krig.concurrency

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
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

/** Priority for [Resource.seize]. Higher wins; ties by arrival. */
public sealed interface ResourcePriority : Comparable<ResourcePriority> {
    public val level: Int

    override fun compareTo(other: ResourcePriority): Int = level.compareTo(other.level)

    public data object Lowest : ResourcePriority { override val level: Int = -2 }
    public data object Low : ResourcePriority { override val level: Int = -1 }
    public data object Normal : ResourcePriority { override val level: Int = 0 }
    public data object High : ResourcePriority { override val level: Int = 1 }
    public data object Critical : ResourcePriority { override val level: Int = 2 }

    /** Application-specific priority level. */
    public data class Custom(override val level: Int) : ResourcePriority

    public companion object {
        public val DEFAULT: ResourcePriority = Normal
    }
}

/** Thrown at a waiter or holder preempted by a higher-priority resource claim. */
public class ResourcePreemptedException(
    public val resourceName: String,
    message: String = "Resource '$resourceName' preempted",
) : CancellationException(message)

/**
 * Capacity-bounded claimable resource. API shape follows kalasim's `Resource` (MIT).
 * One short KMP monitor guards all state; preemption is cooperative via [ResourcePreemptedException].
 */
public class Resource(
    public val name: String,
    public val capacity: Int,
) {
    init {
        require(capacity > 0) { "Resource '$name' capacity must be positive, got $capacity" }
    }

    @OptIn(InternalCoroutinesApi::class)
    private val lock = SynchronizedObject()

    // All mutable state below is accessed under [lock].
    private var usedUnits: Int = 0
    private var seqCounter: Long = 0L
    private val waiters: ArrayDeque<Waiter> = ArrayDeque()
    private val holders: MutableList<Holder> = mutableListOf()
    private val stateFlow = MutableStateFlow(ResourceState(capacity, 0, 0))

    /** Observable `(capacity, used, waiting)` snapshot. */
    public val state: StateFlow<ResourceState> = stateFlow.asStateFlow()

    private data class Holder(
        val amount: Int,
        val priority: ResourcePriority,
        val job: Job?,
    )

    private data class Waiter(
        val amount: Int,
        val priority: ResourcePriority,
        val seqno: Long,
        val continuation: CancellableContinuation<Unit>,
        val holder: Holder?,
    )

    /**
     * Suspends until [amount] units are available, then claims them. Priority order, FIFO on ties.
     * Prefer [use]. Throws [ResourcePreemptedException] on explicit preemption.
     */
    @OptIn(InternalCoroutinesApi::class)
    public suspend fun seize(
        amount: Int = 1,
        priority: ResourcePriority = ResourcePriority.DEFAULT,
    ): Unit = claim(amount, priority, holder = null)

    @OptIn(InternalCoroutinesApi::class)
    private suspend fun claim(
        amount: Int,
        priority: ResourcePriority,
        holder: Holder?,
    ) {
        require(amount in 1..capacity) {
            "seize amount must be in 1..$capacity (resource '$name'), got $amount"
        }
        // Fast path: try to claim synchronously under the lock.
        synchronized(lock) {
            if (usedUnits + amount <= capacity && waiters.isEmpty()) {
                grantLocked(amount, holder)
                return
            }
        }
        // Slow path: enqueue and suspend.
        suspendCancellableCoroutine { cont ->
            val toResume = synchronized(lock) {
                if (usedUnits + amount <= capacity && waiters.isEmpty()) {
                    val waiter = Waiter(amount, priority, -1, cont, holder)
                    grantLocked(waiter)
                    listOf(waiter)
                } else {
                    val waiter = Waiter(amount, priority, ++seqCounter, cont, holder)
                    cont.invokeOnCancellation {
                        synchronized(lock) {
                            removeWaiterLocked(waiter)
                        }
                    }
                    insertWaiterLocked(waiter)
                    drainWaitersLocked()
                }
            }
            resumeGranted(toResume)
        }
    }

    /**
     * Runs [block] holding [amount] units, releasing them afterwards even on exception.
     * Canonical way to claim a resource in client code.
     */
    public suspend fun <R> use(
        amount: Int = 1,
        priority: ResourcePriority = ResourcePriority.DEFAULT,
        block: suspend () -> R,
    ): R {
        val holder = Holder(amount, priority, currentCoroutineContext()[Job])
        claim(amount, priority, holder)
        try { return block() } finally { release(holder) }
    }

    /** Releases [amount] units and wakes waiters in priority order. */
    @OptIn(InternalCoroutinesApi::class)
    public fun release(amount: Int = 1) {
        val toResume = synchronized(lock) {
            require(usedUnits >= amount) {
                "Cannot release $amount units of '$name' — only $usedUnits held"
            }
            usedUnits -= amount
            val granted = drainWaitersLocked()
            publishState()
            granted
        }
        resumeGranted(toResume)
    }

    /**
     * Preempts every waiter whose priority is strictly less than [priority]. Does not
     * affect already-granted holders; use [preemptHoldersBelow] for active claims.
     */
    @OptIn(InternalCoroutinesApi::class)
    public fun preemptWaitersBelow(priority: ResourcePriority) {
        val toCancel = synchronized(lock) {
            val victims = waiters.filter { it.priority < priority }
            victims.forEach(waiters::remove)
            publishState()
            victims
        }
        toCancel.forEach { it.continuation.cancel(ResourcePreemptedException(name)) }
    }

    /** Cooperatively cancels active holders whose priority is strictly less than [priority]. */
    @OptIn(InternalCoroutinesApi::class)
    public fun preemptHoldersBelow(priority: ResourcePriority) {
        val victims = synchronized(lock) {
            holders
                .filter { holder -> holder.priority < priority }
                .mapNotNull { holder -> holder.job }
                .distinct()
        }
        victims.forEach { job -> job.cancel(ResourcePreemptedException(name)) }
    }

    /** Preempts both queued waiters and active holders below [priority]. */
    public fun preemptBelow(priority: ResourcePriority) {
        preemptWaitersBelow(priority)
        preemptHoldersBelow(priority)
    }

    // --- locked helpers (caller holds [lock]) ------------------------------------

    private fun grantLocked(amount: Int, holder: Holder?) {
        usedUnits += amount
        if (holder != null) holders += holder
        publishState()
    }

    private fun grantLocked(waiter: Waiter) {
        grantLocked(waiter.amount, waiter.holder)
    }

    private fun insertWaiterLocked(waiter: Waiter) {
        // Higher priority first; within same priority, earlier seqno first.
        val ix = waiters.indexOfFirst { existing ->
            existing.priority < waiter.priority ||
                (existing.priority == waiter.priority && existing.seqno > waiter.seqno)
        }
        if (ix < 0) waiters.addLast(waiter) else waiters.add(ix, waiter)
        publishState()
    }

    private fun removeWaiterLocked(waiter: Waiter) {
        if (waiters.remove(waiter)) publishState()
    }

    private fun drainWaitersLocked(): List<Waiter> {
        val granted = mutableListOf<Waiter>()
        while (waiters.isNotEmpty()) {
            val head = waiters.first()
            if (usedUnits + head.amount <= capacity) {
                waiters.removeFirst()
                grantLocked(head)
                granted += head
            } else return granted
        }
        return granted
    }

    private fun publishState() {
        stateFlow.value = ResourceState(capacity, usedUnits, waiters.size)
    }

    /** Internal inspection for tests. */
    @OptIn(InternalCoroutinesApi::class)
    @InternalKrigApi
    public fun currentWaiterCount(): Int = synchronized(lock) { waiters.size }

    @OptIn(InternalCoroutinesApi::class)
    private fun rollbackGranted(waiter: Waiter): List<Waiter> = synchronized(lock) {
        if (usedUnits < waiter.amount) return@synchronized emptyList()
        if (waiter.holder != null) holders.remove(waiter.holder)
        usedUnits -= waiter.amount
        val granted = drainWaitersLocked()
        publishState()
        granted
    }

    @OptIn(InternalCoroutinesApi::class)
    private fun release(holder: Holder) {
        val toResume = synchronized(lock) {
            check(holders.remove(holder)) {
                "Cannot release a resource holder that is not active for '$name'"
            }
            check(usedUnits >= holder.amount) {
                "Cannot release ${holder.amount} units of '$name' — only $usedUnits held"
            }
            usedUnits -= holder.amount
            val granted = drainWaitersLocked()
            publishState()
            granted
        }
        resumeGranted(toResume)
    }

    private fun resumeGranted(waiters: List<Waiter>) {
        waiters.forEach { waiter ->
            waiter.continuation.resume(Unit) { _, _, _ ->
                resumeGranted(rollbackGranted(waiter))
            }
        }
    }
}

/** Read-only snapshot of a [Resource]'s state. */
public data class ResourceState(
    public val capacity: Int,
    public val used: Int,
    public val waiting: Int,
) {
    public val available: Int get() = capacity - used
}
