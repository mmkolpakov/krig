package space.kscience.krig.core.pipeline

import space.kscience.dataforge.names.Name
import space.kscience.krig.api.descriptors.attributes.ResourceLock
import space.kscience.krig.api.faults.OperationFault

/** Runtime request to arbitrate resource acquisition before lock waiting starts. */
public data class ResourceArbitrationRequest(
    public val context: OperationContext,
    public val locks: List<ResourceLock>,
    public val heldLocks: Set<Name>,
)

/**
 * Runtime policy deciding whether an operation may enter the resource-lock acquisition path.
 *
 * The default policy is non-preemptive and preserves FIFO mutex semantics. Implementations that need
 * emergency or safety behavior can fail fast or request an explicit preemption plan without changing
 * descriptor-level [ResourceLock] metadata.
 */
public fun interface ResourceArbitrationPolicy {
    public fun arbitrate(request: ResourceArbitrationRequest): ResourceArbitrationDecision
}

/** Decision returned by [ResourceArbitrationPolicy]. */
public sealed interface ResourceArbitrationDecision {
    /** Continue with ordinary deterministic lock acquisition. */
    public data object Acquire : ResourceArbitrationDecision

    /** Fail the operation before entering the wait queue. */
    public data class Reject(public val fault: OperationFault) : ResourceArbitrationDecision

    /**
     * Request cooperative preemption before acquisition.
     *
     * The current production lock registry is deliberately non-preemptive; until a preemptive
     * executor is installed, this decision fails closed instead of silently degrading to a wait.
     */
    public data class Preempt(public val plan: ResourcePreemptionPlan) : ResourceArbitrationDecision
}

/** Explicit preemption intent produced by arbitration policies. */
public data class ResourcePreemptionPlan(
    public val resources: Set<Name>,
    public val reason: String,
    public val requiresSafeState: Boolean = true,
)

/** Built-in resource-arbitration policies. */
public object ResourceArbitrationPolicies {
    public val NonPreemptive: ResourceArbitrationPolicy =
        ResourceArbitrationPolicy { ResourceArbitrationDecision.Acquire }
}
