package space.kscience.krig.concurrency

import kotlinx.coroutines.CancellationException

/** A concrete resource claim. */
public data class ResourceClaim(
    public val id: Long,
    public val amount: Int,
    public val priority: ResourcePriority,
)

/** Input for [ResourcePreemptionPolicy]. */
public data class ResourcePreemptionContext(
    public val request: ResourceClaim,
    public val available: Int,
    public val holders: List<ResourceClaim>,
)

/** Selects active holders to cancel for a waiting request. */
public fun interface ResourcePreemptionPolicy {
    public fun selectVictims(context: ResourcePreemptionContext): List<ResourceClaim>
}

/** Built-in preemption policies. */
public object ResourcePreemptionPolicies {
    public val None: ResourcePreemptionPolicy = ResourcePreemptionPolicy { emptyList() }

    public val Priority: ResourcePreemptionPolicy = ResourcePreemptionPolicy { context ->
        if (context.available >= context.request.amount) return@ResourcePreemptionPolicy emptyList()

        var available = context.available
        val victims = mutableListOf<ResourceClaim>()
        val candidates = context.holders
            .asSequence()
            .filter { holder -> holder.priority < context.request.priority }
            .sortedWith(compareBy<ResourceClaim> { it.priority.level }.thenBy { it.id })

        for (holder in candidates) {
            victims += holder
            available += holder.amount
            if (available >= context.request.amount) return@ResourcePreemptionPolicy victims
        }

        emptyList()
    }
}

/** Describes a preempted resource claim. */
public data class ResourcePreemptionCause(
    public val resourceName: String,
    public val requestedPriority: ResourcePriority,
    public val holderPriority: ResourcePriority? = null,
    public val amount: Int? = null,
)

/** Cancellation thrown at a preempted waiter or holder. */
public class ResourcePreemptedException(
    public val preemption: ResourcePreemptionCause,
    message: String = "Resource '${preemption.resourceName}' preempted",
) : CancellationException(message) {
    public val resourceName: String get() = preemption.resourceName

    public constructor(
        resourceName: String,
        message: String = "Resource '$resourceName' preempted",
    ) : this(ResourcePreemptionCause(resourceName, ResourcePriority.DEFAULT), message)
}
