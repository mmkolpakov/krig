package space.kscience.krig.core.pipeline

import kotlin.time.Duration
import space.kscience.krig.api.faults.DeviceFault
import space.kscience.krig.api.result.DeviceOutcome
import space.kscience.krig.api.spec.RetryPolicy
import space.kscience.krig.core.meta.DeviceActionContract

/** Fail-fast precondition for actions. See [ReadGate]. */
public fun interface ActionGate {
    public suspend fun check(spec: DeviceActionContract<*, *>): DeviceOutcome<Unit>
}

/** After-call observer for actions. See [ReadObserver]. */
public fun interface ActionObserver {
    public suspend fun onAction(
        spec: DeviceActionContract<*, *>,
        durationNanos: Long,
        fault: DeviceFault?,
    )
}

/** Action-side QoS pipeline. Mirrors [ReadPipelineSpec]. */
public data class ActionPipelineSpec(
    public val gates: List<ActionGate> = emptyList(),
    public val observers: List<ActionObserver> = emptyList(),
    public val defaultTimeout: Duration? = null,
    public val defaultRetry: RetryPolicy? = null,
    public val defaultLatencyBudget: Duration? = null,
) {
    public companion object {
        public val Empty: ActionPipelineSpec = ActionPipelineSpec()
    }
}
