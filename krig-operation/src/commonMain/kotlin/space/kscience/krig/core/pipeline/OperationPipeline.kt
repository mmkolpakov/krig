package space.kscience.krig.core.pipeline

import kotlin.jvm.JvmInline
import kotlin.time.Duration
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName
import space.kscience.krig.api.descriptors.OperationDescriptor
import space.kscience.krig.api.descriptors.attributes.ResourceLock
import space.kscience.krig.api.descriptors.attributes.RetryPolicy
import space.kscience.krig.api.faults.OperationFault
import space.kscience.krig.api.result.OperationOutcome
import space.kscience.krig.core.InternalKrigApi

/** Open operation family key. Built-ins cover devices; integrations add their own [Name]s. */
@JvmInline
public value class OperationKind(public val name: Name) {
    override fun toString(): String = name.toString()
}

/** Standard operation kinds used by device pipelines. */
public object OperationKinds {
    public val Read: OperationKind = OperationKind("read".asName())
    public val Write: OperationKind = OperationKind("write".asName())
    public val Action: OperationKind = OperationKind("action".asName())
}

/** Standard operation names for synthetic pipeline invocations. */
public object OperationNames {
    public val BatchRead: Name = "batch.read".asName()
    public val BatchReadBinary: Name = "batch.read.binary".asName()
    public val BatchWrite: Name = "batch.write".asName()
}

/** Runtime description of one operation flowing through a pipeline. */
public data class OperationContext(
    public val kind: OperationKind,
    public val name: Name,
    public val descriptor: OperationDescriptor,
    public val hostName: Name? = null,
)

/** Policy resolved from operation defaults and the concrete descriptor. */
@InternalKrigApi
public data class OperationPolicy(
    public val timeout: Duration? = null,
    public val retry: RetryPolicy? = null,
    public val locks: List<ResourceLock> = emptyList(),
    public val resourceArbitration: ResourceArbitrationPolicy = ResourceArbitrationPolicies.NonPreemptive,
)

/**
 * Reusable, cacheable header for one operation descriptor: the QoS-relevant [context] and [policy].
 *
 * The per-call work — the [OperationTerminal] — is intentionally **not** a field here: keeping it out
 * lets a single header be memoized per property (see `PipelineEngine`) and reused across invocations,
 * while the executor receives the terminal as a separate argument. That removes the per-call
 * `OperationContext`/`OperationPolicy`/header allocations on the dynamic Meta path, leaving only the
 * unavoidable operation closure.
 */
@InternalKrigApi
public data class OperationPlan(
    public val context: OperationContext,
    public val policy: OperationPolicy,
)

/** Per-call terminal of an operation: maps an erased payload to an erased outcome under the QoS chain. */
public typealias OperationTerminal = suspend (payload: Any?) -> OperationOutcome<Any?>

/** Fail-fast precondition for an operation. */
public fun interface OperationGate {
    public suspend fun check(context: OperationContext): OperationOutcome<Unit>
}

/** After-call observer for an operation. */
public fun interface OperationObserver {
    public suspend fun observe(
        context: OperationContext,
        durationNanos: Long,
        fault: OperationFault?,
    )
}

/**
 * How a backend services a multi-property batch, which decides how member timeouts aggregate.
 *
 * - [Sequential] (default): members are serviced one after another (the conservative
 *   `DeviceBackend`/`Device` fallback). The whole-batch budget is the **sum** of member budgets, so a
 *   100-property batch of 2 ms reads is not forced into a single 2 ms deadline.
 * - [Coalescing]: members are serviced as one transaction (e.g. an OPC UA read or a Modbus block),
 *   so the budget is the **maximum** member budget. A batch-capable driver declares this on its
 *   pipeline (`PipelineBuilder.batchExecutionMode`).
 */
public enum class BatchExecutionMode { Sequential, Coalescing }

/** Operation-level QoS policy. */
public data class OperationPipelineSpec(
    public val gates: List<OperationGate> = emptyList(),
    public val observers: List<OperationObserver> = emptyList(),
    public val defaultTimeout: Duration? = null,
    public val defaultRetry: RetryPolicy? = null,
    public val defaultLatencyBudget: Duration? = null,
    /**
     * When `true`, descriptor-level operational QoS (timeout/retry authored in the manifest for
     * real hardware) is ignored and only the kind-level defaults apply. Set by runtime profiles
     * such as `PipelineProfile.InMemory`, where transport deadlines are meaningless.
     */
    public val suppressDescriptorQos: Boolean = false,
    /** Batch service mode for this kind; drives whole-batch timeout aggregation. */
    public val batchExecutionMode: BatchExecutionMode = BatchExecutionMode.Sequential,
    /** Runtime resource arbitration. Default keeps ordinary non-preemptive lock acquisition. */
    public val resourceArbitration: ResourceArbitrationPolicy = ResourceArbitrationPolicies.NonPreemptive,
) {
    public companion object {
        public val Empty: OperationPipelineSpec = OperationPipelineSpec()
    }
}
