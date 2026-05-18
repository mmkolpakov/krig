package space.kscience.krig.core.contracts

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Duration
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import space.kscience.dataforge.names.Name
import space.kscience.krig.api.faults.DeviceFaultException
import space.kscience.krig.api.faults.InvalidStateFault
import space.kscience.krig.core.InternalKrigApi

@OptIn(ExperimentalAtomicApi::class, InternalKrigApi::class)
internal class GracefulOperationController(
    private val name: Name,
) : OperationTracker {
    private val state: AtomicInt = AtomicInt(OPERATION_ACTIVE)
    private val waiterLock = SynchronizedObject()
    private var drainWaiter: CompletableDeferred<Unit>? = null
    private var shutdownWaiter: CompletableDeferred<Unit>? = null

    override fun enterOperation() {
        while (true) {
            val current = state.load()
            when {
                current.isClosed -> rejectOperation("Closed")
                current.isDraining -> rejectOperation("Draining")
                current.inflight == OPERATION_INFLIGHT_MASK ->
                    error("Too many in-flight operations for '$name'.")
            }
            if (state.compareAndSet(current, current + 1)) return
        }
    }

    override fun exitOperation() {
        while (true) {
            val current = state.load()
            val inflight = current.inflight
            check(inflight > 0) { "Unbalanced device operation accounting for '$name'." }
            val next = current.flags or inflight - 1
            if (state.compareAndSet(current, next)) {
                if (current.isDraining && inflight == 1) completeDrainWaiter()
                return
            }
        }
    }

    suspend fun closeGracefully(
        drainTimeout: Duration,
        shutdownBlock: suspend () -> Unit,
    ) {
        val plan = beginClose() ?: return
        if (plan.owner) {
            try {
                val _ = plan.drainWaiter?.let { waiter ->
                    withTimeoutOrNull(drainTimeout) { waiter.await() }
                }
                shutdownBlock()
            } finally {
                finishClose()
            }
        } else {
            withTimeoutOrNull(drainTimeout) { plan.shutdownWaiter.await() }
        }
    }

    private fun beginClose(): GracefulClosePlan? {
        while (true) {
            val current = state.load()
            when {
                current.isClosed -> return null
                current.isDraining -> {
                    val existing = synchronized(waiterLock) {
                        val latest = state.load()
                        when {
                            latest.isClosed -> null
                            latest.isDraining -> shutdownWaiter?.let { GracefulClosePlan(drainWaiter, it, owner = false) }
                            else -> null
                        }
                    }
                    if (existing != null || state.load().isClosed) return existing
                    continue
                }
            }

            val plan = synchronized(waiterLock) {
                val latest = state.load()
                if (latest != current || latest.isDraining || latest.isClosed) {
                    null
                } else {
                    val newDrainWaiter = if (latest.inflight > 0) CompletableDeferred<Unit>() else null
                    val newShutdownWaiter = CompletableDeferred<Unit>()
                    drainWaiter = newDrainWaiter
                    shutdownWaiter = newShutdownWaiter
                    if (state.compareAndSet(latest, latest or OPERATION_DRAINING_FLAG)) {
                        GracefulClosePlan(newDrainWaiter, newShutdownWaiter, owner = true)
                    } else {
                        null
                    }
                }
            }
            if (plan != null) return plan
        }
    }

    private fun finishClose() {
        val waiter: CompletableDeferred<Unit>? = synchronized(waiterLock) {
            var result: CompletableDeferred<Unit>? = null
            var done = false
            while (!done) {
                val current = state.load()
                if (current.isClosed) {
                    done = true
                    continue
                }
                val next = OPERATION_CLOSED_FLAG or current.inflight
                if (state.compareAndSet(current, next)) {
                    result = shutdownWaiter
                    drainWaiter = null
                    shutdownWaiter = null
                    done = true
                }
            }
            result
        }
        waiter?.complete(Unit)
    }

    private fun completeDrainWaiter() {
        val waiter: CompletableDeferred<Unit>? = synchronized(waiterLock) {
            val current = state.load()
            if (current.isDraining && current.inflight == 0) {
                drainWaiter.also { drainWaiter = null }
            } else {
                null
            }
        }
        waiter?.complete(Unit)
    }

    private fun rejectOperation(currentState: String): Nothing {
        throw DeviceFaultException(
            InvalidStateFault(
                currentState = currentState,
                requiredState = "Active",
                operation = "operate '$name'",
            ),
        )
    }
}

private data class GracefulClosePlan(
    val drainWaiter: CompletableDeferred<Unit>?,
    val shutdownWaiter: CompletableDeferred<Unit>,
    val owner: Boolean,
)

private const val OPERATION_INFLIGHT_MASK: Int = 0x3fff_ffff
private const val OPERATION_DRAINING_FLAG: Int = 0x4000_0000
private const val OPERATION_CLOSED_FLAG: Int = Int.MIN_VALUE
private const val OPERATION_ACTIVE: Int = 0

private val Int.inflight: Int get() = this and OPERATION_INFLIGHT_MASK
private val Int.flags: Int get() = this and OPERATION_INFLIGHT_MASK.inv()
private val Int.isDraining: Boolean get() = this and OPERATION_DRAINING_FLAG != 0
private val Int.isClosed: Boolean get() = this and OPERATION_CLOSED_FLAG != 0
