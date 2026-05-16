package space.kscience.krig.api.faults

import kotlin.reflect.KClass
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Recovery action for a [DeviceFault]. Default mapping in [FaultRecoveryPolicy];
 * driver-specific recoveries go through [Custom] keyed by [Custom.tag].
 */
public sealed interface Recovery {
    /** Reject the offending command/input; no retry. Default for [ValidationFault]. */
    public data object Reject : Recovery

    /** Escalate to an external policy/operator; no silent retry. Default for [AuthorizationFault]. */
    public data object Escalate : Recovery

    /** Retry after [delay]. Default for [TimeoutFault] and transient transport errors. */
    public data class RetryAfter(public val delay: Duration) : Recovery

    /** No action — caller decides. Fallback for unknown faults. */
    public data object FallThrough : Recovery

    /** Driver-specific recovery keyed by [tag] (`"reset-power"`, `"purge-buffer"`, ...). */
    public data class Custom(
        public val tag: String,
        public val payload: space.kscience.dataforge.meta.Meta = space.kscience.dataforge.meta.Meta.EMPTY,
    ) : Recovery
}

/**
 * Fault → [Recovery] classifier. Lookup: exact-class override → supertype override → SDK default.
 *
 * ```kotlin
 * FaultRecoveryPolicy.default()
 *     .override(MyFault::class) { Recovery.Custom("recalibrate-axis") }
 *     .override<TimeoutFault> { Recovery.RetryAfter(2.seconds) }
 * ```
 */
public class FaultRecoveryPolicy internal constructor(
    private val overrides: Map<KClass<out DeviceFault>, (DeviceFault) -> Recovery>,
) {
    public fun classify(fault: DeviceFault): Recovery {
        overrides[fault::class]?.let { return it(fault) }
        for ((klass, fn) in overrides) {
            if (klass.isInstance(fault)) return fn(fault)
        }
        return defaultClassify(fault)
    }

    public fun <F : DeviceFault> override(
        faultClass: KClass<F>,
        recovery: (F) -> Recovery,
    ): FaultRecoveryPolicy {
        @Suppress("UNCHECKED_CAST")
        val casted = recovery as (DeviceFault) -> Recovery
        return FaultRecoveryPolicy(overrides + (faultClass to casted))
    }

    public inline fun <reified F : DeviceFault> override(
        noinline recovery: (F) -> Recovery,
    ): FaultRecoveryPolicy = override(F::class, recovery)

    public fun withContributions(contributions: Iterable<Contribution>): FaultRecoveryPolicy =
        contributions.fold(this) { acc, c -> c.apply(acc) }

    /** Stateless, idempotent overlay. Discovered on JVM via `ServiceLoader`; explicit elsewhere. */
    public fun interface Contribution {
        public fun apply(base: FaultRecoveryPolicy): FaultRecoveryPolicy
    }

    public companion object {
        /** SDK-wide default. Integrations chain [override] on top. */
        public fun default(): FaultRecoveryPolicy = FaultRecoveryPolicy(emptyMap())

        internal fun defaultClassify(fault: DeviceFault): Recovery = when (fault) {
            is ValidationFault -> Recovery.Reject
            is AuthorizationFault -> Recovery.Escalate
            is TimeoutFault -> Recovery.RetryAfter(100.milliseconds)
            else -> Recovery.FallThrough
        }
    }
}
