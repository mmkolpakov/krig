package space.kscience.krig.concurrency

/** Resource trace event type — closed vocabulary of the simulation semaphore. */
public enum class ResourceEventType { Requested, Queued, Granted, Released, Preempted }

/** Lightweight resource event for simulation traces. */
public data class ResourceEvent(
    public val type: ResourceEventType,
    public val resourceName: String,
    public val claim: ResourceClaim,
    public val capacity: Int,
    public val used: Int,
    public val waiting: Int,
)

/** Receives resource trace events. */
public fun interface ResourceEventSink {
    public fun emit(event: ResourceEvent)

    public companion object {
        public val None: ResourceEventSink = ResourceEventSink { }
    }
}
