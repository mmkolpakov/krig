package space.kscience.krig.api.lifecycle

/**
 * Vocabulary for device / port lifecycle. The FSM transition table is keyed on
 * [kotlin.reflect.KClass], so all [Failed] values share one row regardless of [Failed.cause].
 *
 * `data class Failed(cause)` carries cause-sensitive equality: a [StateFlow][kotlinx.coroutines.flow.StateFlow]
 * re-emits on every distinct [Throwable], and conflates consecutive [Failed.NoCause] writes.
 *
 * LifecycleState is a runtime-only FSM. [Failed] states are not wire-serializable —
 * wrap the cause in a [OperationFault][space.kscience.krig.api.faults.OperationFault] or
 * transport-native error envelope when crossing the wire. A serializable LifecycleState
 * DTO is planned for a future release.
 */
public sealed interface LifecycleState {
    public data object Detached : LifecycleState

    public data object Attaching : LifecycleState

    public data object Stopped : LifecycleState

    public data object Starting : LifecycleState

    public data object Running : LifecycleState

    /**
     * Running but intentionally held: awaiting material, blocked by an interlock, or operator-paused.
     * Distinct from [Stopped] (not shut down) and [Failed] (not faulted) — the device keeps its
     * resources and can resume to [Running]. Gated operations are denied while suspended.
     */
    public data object Suspended : LifecycleState

    public data object Stopping : LifecycleState

    public data object Detaching : LifecycleState

    public data class Failed(public val cause: Throwable? = null) : LifecycleState {
        public companion object {
            public val NoCause: Failed = Failed(null)
        }
    }
}
