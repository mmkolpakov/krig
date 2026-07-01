@file:OptIn(InternalKrigApi::class)

package space.kscience.krig.core.pipeline

import kotlinx.coroutines.currentCoroutineContext
import space.kscience.dataforge.meta.Meta
import space.kscience.krig.api.faults.GenericOperationFault
import space.kscience.krig.api.faults.OperationFaultDetails
import space.kscience.krig.api.faults.OperationFaultTypes
import space.kscience.krig.api.result.OperationOutcome
import space.kscience.krig.core.InternalKrigApi
import space.kscience.krig.core.operations.ResourceLockRegistry

/**
 * Stable identity of an [OperationInterceptor]. Lets a runtime profile remove or replace a layer in a
 * compiled chain by key (see [without] / [replace]) instead of reasoning about list positions.
 */
public class OperationInterceptorKey(public val id: String) {
    override fun toString(): String = id
    override fun equals(other: Any?): Boolean = other is OperationInterceptorKey && other.id == id
    override fun hashCode(): Int = id.hashCode()
}

/** Erased continuation handed to an interceptor: invoking it runs the remainder of the chain. */
public typealias OperationProceed = suspend (payload: Any?) -> OperationOutcome<Any?>

/**
 * One composable QoS layer over an operation, ordered outside-in (the first element of a chain is the
 * outermost wrapper). Payloads and outcomes are erased to `Any?` because a single chain serves reads,
 * writes and actions — the typed facades validate descriptors and converters before values reach this
 * boundary, so a generic `<I, O>` interceptor would only re-erase at the call site.
 *
 * An interceptor either short-circuits (returning a failure without calling [OperationProceed]) or
 * delegates to `proceed` and optionally post-processes the result. This mirrors gRPC
 * `ServerInterceptor` / Tower `Layer`: one concern per layer, composed by chaining.
 */
public interface OperationInterceptor {
    public val key: OperationInterceptorKey
    public suspend fun intercept(
        plan: OperationPlan,
        payload: Any?,
        proceed: OperationProceed,
    ): OperationOutcome<Any?>
}

/** Keys of the built-in interceptors assembled by [defaultOperationInterceptors], in default order. */
public object BuiltinInterceptorKeys {
    public val Timeout: OperationInterceptorKey = OperationInterceptorKey("builtin.timeout")
    public val Gates: OperationInterceptorKey = OperationInterceptorKey("builtin.gates")
    public val Retry: OperationInterceptorKey = OperationInterceptorKey("builtin.retry")
    public val Locks: OperationInterceptorKey = OperationInterceptorKey("builtin.locks")
}

/** Applies the per-plan global timeout; passthrough when no budget is set. */
@InternalKrigApi
public class TimeoutInterceptor : OperationInterceptor {
    override val key: OperationInterceptorKey get() = BuiltinInterceptorKeys.Timeout
    override suspend fun intercept(
        plan: OperationPlan,
        payload: Any?,
        proceed: OperationProceed,
    ): OperationOutcome<Any?> =
        withGlobalTimeout(plan.policy.timeout, plan.context.name) { proceed(payload) }
}

/** Runs fail-fast preconditions; the first failing gate short-circuits the chain. */
@InternalKrigApi
public class GatesInterceptor(private val gates: List<OperationGate>) : OperationInterceptor {
    override val key: OperationInterceptorKey get() = BuiltinInterceptorKeys.Gates
    override suspend fun intercept(
        plan: OperationPlan,
        payload: Any?,
        proceed: OperationProceed,
    ): OperationOutcome<Any?> {
        for (gate in gates) {
            val gateResult = gate.check(plan.context)
            if (gateResult is OperationOutcome.Fail) return gateResult
        }
        return proceed(payload)
    }
}

/** Retries the remainder of the chain for transient faults per the plan's [RetryPolicy]. */
@InternalKrigApi
public class RetryInterceptor : OperationInterceptor {
    override val key: OperationInterceptorKey get() = BuiltinInterceptorKeys.Retry
    override suspend fun intercept(
        plan: OperationPlan,
        payload: Any?,
        proceed: OperationProceed,
    ): OperationOutcome<Any?> =
        withIoRetry(plan.policy.retry) { proceed(payload) }
}

/** Acquires the plan's resource locks in deterministic order before proceeding. */
@InternalKrigApi
public class LocksInterceptor(private val registry: ResourceLockRegistry) : OperationInterceptor {
    override val key: OperationInterceptorKey get() = BuiltinInterceptorKeys.Locks
    override suspend fun intercept(
        plan: OperationPlan,
        payload: Any?,
        proceed: OperationProceed,
    ): OperationOutcome<Any?> {
        val heldLocks = currentCoroutineContext()[HeldResourceLocks]?.names.orEmpty()
        val decision = plan.policy.resourceArbitration.arbitrate(
            ResourceArbitrationRequest(plan.context, plan.policy.locks, heldLocks),
        )
        return when (decision) {
            ResourceArbitrationDecision.Acquire ->
                acquireAllLocks(registry, plan.policy.locks) { proceed(payload) }

            is ResourceArbitrationDecision.Reject ->
                OperationOutcome.Fail(decision.fault)

            is ResourceArbitrationDecision.Preempt ->
                OperationOutcome.Fail(preemptionUnsupportedFault(plan.context, decision.plan))
        }
    }
}

/**
 * The built-in interceptor chain in safe default order: `timeout → gates → retry → locks → terminal`.
 * Timeout is outermost so it covers gate evaluation and every retry attempt; locks are innermost so a
 * retried attempt re-acquires them. Each layer is a passthrough when its policy field is unset, so a
 * profile that nulls timeouts/retries (e.g. in-memory twin) costs nothing.
 */
@InternalKrigApi
public fun defaultOperationInterceptors(
    gates: List<OperationGate>,
    registry: ResourceLockRegistry,
): List<OperationInterceptor> =
    listOf(
        TimeoutInterceptor(),
        GatesInterceptor(gates),
        RetryInterceptor(),
        LocksInterceptor(registry),
    )

/** Returns a copy of this chain with every layer carrying [key] removed (profile-driven trimming). */
public fun List<OperationInterceptor>.without(key: OperationInterceptorKey): List<OperationInterceptor> =
    filterNot { it.key == key }

/** Returns a copy of this chain with each layer carrying [key] replaced by [replacement]. */
public fun List<OperationInterceptor>.replace(
    key: OperationInterceptorKey,
    replacement: OperationInterceptor,
): List<OperationInterceptor> =
    map { if (it.key == key) replacement else it }

private fun preemptionUnsupportedFault(
    context: OperationContext,
    plan: ResourcePreemptionPlan,
): GenericOperationFault =
    GenericOperationFault(
        faultType = OperationFaultTypes.InvalidState,
        message = "Resource preemption is not active for operation '${context.name}'.",
        details = Meta {
            OperationFaultDetails.OPERATION put context.name.toString()
            "resources" put plan.resources.joinToString(",") { it.toString() }
            "reason" put plan.reason
            "requiresSafeState" put plan.requiresSafeState.toString()
        },
    )
