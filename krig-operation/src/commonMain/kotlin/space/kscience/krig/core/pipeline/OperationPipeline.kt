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
)

/** Reusable invocation plan for one operation descriptor. */
@InternalKrigApi
public data class OperationPlan(
    public val context: OperationContext,
    public val policy: OperationPolicy,
    public val terminal: suspend (payload: Any?) -> OperationOutcome<Any?>,
)

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

/** Operation-level QoS policy. */
public data class OperationPipelineSpec(
    public val gates: List<OperationGate> = emptyList(),
    public val observers: List<OperationObserver> = emptyList(),
    public val defaultTimeout: Duration? = null,
    public val defaultRetry: RetryPolicy? = null,
    public val defaultLatencyBudget: Duration? = null,
) {
    public companion object {
        public val Empty: OperationPipelineSpec = OperationPipelineSpec()
    }
}
