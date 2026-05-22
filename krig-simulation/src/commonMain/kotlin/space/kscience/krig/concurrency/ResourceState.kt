package space.kscience.krig.concurrency

/** Read-only snapshot of a [Resource]'s state. */
public data class ResourceState(
    public val capacity: Int,
    public val used: Int,
    public val waiting: Int,
) {
    public val available: Int get() = capacity - used
}
