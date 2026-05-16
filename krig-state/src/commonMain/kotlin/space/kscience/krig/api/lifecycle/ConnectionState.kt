package space.kscience.krig.api.lifecycle

/**
 * Transport-level state of a device backend. Orthogonal to [LifecycleState]:
 * a `Running` device may have a `Disconnected` transport during a network partition.
 * Protocol adapters add their own states via [SerializationContributor][space.kscience.krig.api.serialization.SerializationContributor].
 */
public sealed interface ConnectionState {
    public data object Connected : ConnectionState

    /** Transport is down; reads may return stale cached data. */
    public data object Disconnected : ConnectionState

    public data object Connecting : ConnectionState
}
