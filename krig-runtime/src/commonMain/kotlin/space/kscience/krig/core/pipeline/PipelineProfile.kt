package space.kscience.krig.core.pipeline

/**
 * Runtime QoS profile applied to a [PipelineBuilder] **after** its features / manifest defaults are
 * installed. Operational QoS supplied by a manifest or pipeline feature
 * (timeout / retry / latency budget) is treated as a *default* that a profile may override — a
 * laminate where `manifest defaults < profile`. Semantic gates, observers, capabilities and lifecycle
 * are left untouched; a profile only rewrites operational QoS.
 */
public sealed interface PipelineProfile {
    /** Rewrites operational QoS on [builder]. Called after manifest/feature install. */
    public fun applyTo(builder: PipelineBuilder)

    /** Identity profile: keep manifest/feature QoS exactly as declared (production default). */
    public data object Production : PipelineProfile {
        override fun applyTo(builder: PipelineBuilder) {
            // Manifest/feature QoS is the source of truth — nothing to override.
        }
    }

    /**
     * Digital-twin / in-memory profile: clears network-oriented operational QoS (timeouts, retries,
     * latency budgets) for read/write/action — both the kind-level defaults and the
     * descriptor-level QoS authored in manifests for real hardware (`suppressDescriptorQos`).
     * An in-process model has no transport to time out or retry, so those deadlines are meaningless.
     */
    public data object InMemory : PipelineProfile {
        override fun applyTo(builder: PipelineBuilder) {
            for (kind in OPERATIONAL_KINDS) {
                builder.timeout(kind, null)
                builder.retry(kind, null)
                builder.latencyBudget(kind, null)
            }
            builder.suppressDescriptorQos()
        }

        private val OPERATIONAL_KINDS =
            listOf(OperationKinds.Read, OperationKinds.Write, OperationKinds.Action)
    }
}

/** Scope for the `pipeline { … }` device-DSL block. */
public class PipelineProfileScope internal constructor() {
    internal var profile: PipelineProfile = PipelineProfile.Production
        private set

    /** Selects the runtime QoS [profile] for this device's pipeline. */
    public fun profile(profile: PipelineProfile) {
        this.profile = profile
    }
}
