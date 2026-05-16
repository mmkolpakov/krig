package space.kscience.krig.core.pipeline

import kotlin.time.Duration
import space.kscience.krig.api.faults.DeviceFault
import space.kscience.krig.api.spec.RetryPolicy
import space.kscience.krig.core.meta.MutableDevicePropertySpec

/** Fail-fast precondition for typed writes. See [ReadGate]. */
public fun interface WriteGate {
    public suspend fun check(spec: MutableDevicePropertySpec<*, *>)
}

/** After-call observer for typed writes. See [ReadObserver]. */
public fun interface WriteObserver {
    public suspend fun onWrite(
        spec: MutableDevicePropertySpec<*, *>,
        durationNanos: Long,
        fault: DeviceFault?,
    )
}

/** Write-side QoS pipeline. Mirrors [ReadPipelineSpec]. */
public data class WritePipelineSpec(
    public val gates: List<WriteGate> = emptyList(),
    public val observers: List<WriteObserver> = emptyList(),
    public val defaultTimeout: Duration? = null,
    public val defaultRetry: RetryPolicy? = null,
    public val defaultLatencyBudget: Duration? = null,
) {
    public companion object {
        public val Empty: WritePipelineSpec = WritePipelineSpec()
    }
}
