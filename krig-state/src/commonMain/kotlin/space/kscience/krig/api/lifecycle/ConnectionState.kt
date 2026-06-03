package space.kscience.krig.api.lifecycle

/**
 * Transport-level state of a device backend. Orthogonal to [LifecycleState]:
 * a `Running` device may be `Disconnected` during a network partition. Closed vocabulary —
 * transport semantics are universal, so exhaustiveness is preferred over extension.
 */
public enum class ConnectionState {
    Connected,

    /** Transport is down; reads may return stale cached data. */
    Disconnected,

    Connecting,
}
