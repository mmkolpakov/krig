@file:OptIn(InternalKrigApi::class)

package space.kscience.krig.core.pipeline

import kotlin.time.TimeSource
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import space.kscience.krig.api.faults.OperationFault
import space.kscience.krig.api.faults.OperationFaultException
import space.kscience.krig.api.faults.FaultRetryClassifier
import space.kscience.krig.api.faults.GenericOperationFault
import space.kscience.krig.api.faults.OperationFaultTypes
import space.kscience.krig.api.faults.TimeoutFault
import space.kscience.krig.api.faults.TransportFault
import space.kscience.dataforge.names.Name
import space.kscience.krig.api.descriptors.attributes.ResourceLock
import space.kscience.krig.api.descriptors.attributes.RetryPolicy
import space.kscience.krig.api.result.OperationOutcome
import space.kscience.krig.api.result.faultOrNull
import space.kscience.krig.api.result.ok
import space.kscience.krig.core.InternalKrigApi
import space.kscience.krig.core.operations.ResourceLockRegistry
import kotlin.time.Duration

/**
 * Coroutine-scoped record of resource locks already held on the current call path. Lets a nested
 * operation re-enter a resource its caller holds instead of self-deadlocking on the non-reentrant
 * [kotlinx.coroutines.sync.Mutex] (same coroutine = sequential, so re-entry is safe).
 */
@InternalKrigApi
public class HeldResourceLocks(public val names: Set<Name>) :
    AbstractCoroutineContextElement(Key) {
    public companion object Key : CoroutineContext.Key<HeldResourceLocks>
}

/**
 * Locks each resource once in deterministic order (exclusive; duplicate names merged). Resources
 * already held by the caller (see [HeldResourceLocks]) are skipped, so re-entrant operation chains
 * do not deadlock the non-reentrant mutex.
 */
@OptIn(InternalKrigApi::class)
@InternalKrigApi
public suspend fun <R> acquireAllLocks(
    registry: ResourceLockRegistry,
    locks: List<ResourceLock>,
    block: suspend () -> R,
): R {
    if (locks.isEmpty()) return block()
    val held = currentCoroutineContext()[HeldResourceLocks]?.names.orEmpty()
    val ordered = locks.canonicalizeLocks().filterNot { it.resourceName in held }
    if (ordered.isEmpty()) return block()
    val nextHeld = held + ordered.map { it.resourceName }
    return acquireRecursively(registry, ordered, 0) {
        withContext(HeldResourceLocks(nextHeld)) { block() }
    }
}

private fun List<ResourceLock>.canonicalizeLocks(): List<ResourceLock> =
    distinctBy { it.resourceName }
        .sortedWith { left, right -> compareNames(left.resourceName, right.resourceName) }

/**
 * Deterministic, allocation-free total order over DataForge [Name]s used for lock ordering.
 * Kept local to the pipeline module so krig-state does not depend on DataForge names.
 */
private fun compareNames(left: Name, right: Name): Int {
    val leftTokens = left.tokens
    val rightTokens = right.tokens
    val shared = minOf(leftTokens.size, rightTokens.size)
    for (i in 0 until shared) {
        val byBody = leftTokens[i].body.compareTo(rightTokens[i].body)
        if (byBody != 0) return byBody
        val byIndex = compareValues(leftTokens[i].index, rightTokens[i].index)
        if (byIndex != 0) return byIndex
    }
    return leftTokens.size.compareTo(rightTokens.size)
}

@OptIn(InternalKrigApi::class)
private suspend fun <R> acquireRecursively(
    registry: ResourceLockRegistry,
    ordered: List<ResourceLock>,
    index: Int,
    block: suspend () -> R,
): R {
    if (index == ordered.size) return block()
    val mutex = registry.mutexFor(ordered[index].resourceName)
    return mutex.withLock { acquireRecursively(registry, ordered, index + 1, block) }
}

/** Retry wrapper for predictable transient faults. */
@InternalKrigApi
public suspend fun <R> withIoRetry(
    retry: RetryPolicy?,
    retryClassifier: FaultRetryClassifier = FaultRetryClassifier.Default,
    block: suspend () -> OperationOutcome<R>,
): OperationOutcome<R> {
    val policy = retry ?: return block()
    if (policy.maxAttempts <= 0) return block()
    var retries = 0
    var nextDelay = policy.initialDelay

    while (true) {
        val result = block()
        val fault = result.faultOrNull() ?: return result
        if (!retryClassifier.shouldRetry(fault) || retries >= policy.maxAttempts) return result
        if (nextDelay > Duration.ZERO) delay(nextDelay)
        retries++
        nextDelay = nextRetryDelay(nextDelay, policy)
    }
}

@InternalKrigApi
public suspend fun <R> catchingOperationOutcome(block: suspend () -> R): OperationOutcome<R> =
    try {
        ok(block())
    } catch (ce: CancellationException) {
        throw ce
    } catch (e: OperationFaultException) {
        OperationOutcome.Fail(e.fault)
    } catch (e: kotlinx.io.IOException) {
        OperationOutcome.Fail(
            TransportFault(
                causeType = e::class.simpleName ?: "IOException",
                message = e.message ?: "I/O error",
            ),
        )
    }

@InternalKrigApi
public suspend fun <R> withGlobalTimeout(
    timeout: Duration?,
    operation: Name? = null,
    block: suspend () -> OperationOutcome<R>,
): OperationOutcome<R> =
    if (timeout == null) {
        block()
    } else {
        try {
            withTimeout(timeout) { block() }
        } catch (_: TimeoutCancellationException) {
            OperationOutcome.Fail(TimeoutFault(operation = operation, budget = timeout))
        }
    }

private fun nextRetryDelay(current: Duration, policy: RetryPolicy): Duration {
    if (current == Duration.ZERO || policy.backoffMultiplier == 1.0) return current
    val candidate = current * policy.backoffMultiplier
    return if (candidate > policy.maxDelay) policy.maxDelay else candidate
}

/**
 * Folds this interceptor chain over a fixed [plan] and [terminal] into a single executor, once.
 *
 * The chain is composed outside-in (the first interceptor is the outermost), and [observers] wrap the
 * whole chain with timing and fault capture (always in `finally`, never altering operation semantics).
 * Because the [plan] is baked in here, the result is a zero-argument-header executor that a typed
 * reader/writer/action compiles **once** and reuses across calls — the `proceed` closures are
 * allocated at compile time, not per invocation, so the hot path stays allocation-free.
 */
@InternalKrigApi
public fun List<OperationInterceptor>.compileChain(
    plan: OperationPlan,
    observers: List<OperationObserver>,
    timeSource: TimeSource,
    terminal: OperationTerminal,
): suspend (Any?) -> OperationOutcome<Any?> {
    var proceed: OperationProceed = terminal
    for (index in indices.reversed()) {
        val interceptor = this[index]
        val next = proceed
        proceed = { payload -> interceptor.intercept(plan, payload, next) }
    }
    val chain = proceed
    return { payload -> observeOperation(plan, observers, timeSource) { chain(payload) } }
}

/**
 * Compiles the built-in policy chain (`timeout → gates → retry → locks`) shared by typed readers,
 * writers, and actions, returning an executor that takes the reusable [OperationPlan] header and a
 * per-call [OperationTerminal] separately. Typed facades that read one descriptor repeatedly should
 * instead compile a chain once via [compileChain]; this form serves the dynamic Meta and batch paths
 * where the terminal varies per call.
 */
@InternalKrigApi
public fun compileOperationExecutor(
    gates: List<OperationGate>,
    observers: List<OperationObserver>,
    registry: ResourceLockRegistry,
    timeSource: TimeSource = TimeSource.Monotonic,
): suspend (OperationPlan, Any?, OperationTerminal) -> OperationOutcome<Any?> {
    val interceptors = defaultOperationInterceptors(gates, registry)
    return { plan, payload, terminal ->
        interceptors.compileChain(plan, observers, timeSource, terminal)(payload)
    }
}

/**
 * Runs [block] under observer timing and fault capture. The duration and any fault (returned as a
 * failure, raised as [OperationFaultException], or synthesized from a `Throwable`) are reported to
 * [observers] in `finally`; observer failures are swallowed so observability never changes semantics.
 */
@InternalKrigApi
public suspend fun observeOperation(
    plan: OperationPlan,
    observers: List<OperationObserver>,
    timeSource: TimeSource,
    block: suspend () -> OperationOutcome<Any?>,
): OperationOutcome<Any?> {
    val mark = timeSource.markNow()
    var captured: OperationFault? = null
    try {
        val result = block()
        captured = result.faultOrNull()
        return result
    } catch (e: OperationFaultException) {
        captured = e.fault
        return OperationOutcome.Fail(e.fault)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        captured = faultForThrowable(e)
        throw e
    } finally {
        ignoreObserverFailure {
            val durationNanos = mark.elapsedNow().inWholeNanoseconds
            observers.forEach { observer ->
                observer.observe(plan.context, durationNanos, captured)
            }
        }
    }
}

private fun faultForThrowable(e: Throwable): GenericOperationFault =
    GenericOperationFault(
        faultType = if (e is Exception) OperationFaultTypes.System else OperationFaultTypes.FatalSystem,
        message = e.message ?: e.toString(),
    )

private suspend inline fun ignoreObserverFailure(block: suspend () -> Unit) {
    try {
        block()
    } catch (ce: CancellationException) {
        throw ce
    } catch (_: Exception) {
        // Observability must never change operation semantics; cancellation and fatal errors must.
    }
}
