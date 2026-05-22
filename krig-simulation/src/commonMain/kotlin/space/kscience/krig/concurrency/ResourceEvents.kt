package space.kscience.krig.concurrency

import kotlin.jvm.JvmInline

/** Resource trace event type. */
@JvmInline
public value class ResourceEventType(public val name: String) {
    override fun toString(): String = name

    public companion object {
        public val Requested: ResourceEventType = ResourceEventType("requested")
        public val Queued: ResourceEventType = ResourceEventType("queued")
        public val Granted: ResourceEventType = ResourceEventType("granted")
        public val Released: ResourceEventType = ResourceEventType("released")
        public val Preempted: ResourceEventType = ResourceEventType("preempted")
    }
}

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
