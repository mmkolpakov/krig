package space.kscience.krig.core.pipeline

import space.kscience.krig.api.faults.DeviceFault
import space.kscience.krig.core.contracts.typed.TypedSampler
import space.kscience.krig.core.meta.DevicePropertyContract

/**
 * After-sample observer. Receives the spec and the sampled value (or null on completion).
 * Observers must not throw; the executor wraps invocations defensively.
 *
 * Examples: sample-rate metrics, snapshot-to-store, edge-triggered alerts.
 */
public fun interface SamplingObserver {
    public suspend fun onSample(
        spec: DevicePropertyContract<*>,
        value: Any?,
        fault: DeviceFault?,
    )
}

/**
 * Declarative QoS-style configuration for the sampling (subscription / streaming)
 * pipeline. Parallel to [ReadPipelineSpec] for one-shot reads; this spec controls
 * the continuous sampling path exposed by [TypedSampler].
 *
 * Evolution policy: additive fields with safe defaults — non-breaking.
 */
public data class SamplingPipelineSpec(
    public val observers: List<SamplingObserver> = emptyList(),
) {
    public companion object {
        /** No observers — raw sampler passthrough. */
        public val Empty: SamplingPipelineSpec = SamplingPipelineSpec()
    }
}
