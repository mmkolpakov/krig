package space.kscience.krig.core.pipeline

import kotlin.time.Duration
import space.kscience.krig.api.faults.DeviceFault
import space.kscience.krig.api.result.DeviceOutcome
import space.kscience.krig.api.spec.RetryPolicy
import space.kscience.krig.core.contracts.typed.TypedReader
import space.kscience.krig.core.meta.DevicePropertyContract

/**
 * Fail-fast precondition for typed reads. Returns [DeviceOutcome.Fail] to deny
 * and [DeviceOutcome.Ok] to allow.
 *
 * Examples: principal-aware RBAC, lifecycle gate, connection-state gate.
 *
 * Gates are executed by the device's read-pipeline executor before any lock acquisition
 * or I/O — kept side-effect-free where possible to make denial cheap.
 */
public fun interface ReadGate {
    public suspend fun check(spec: DevicePropertyContract<*>): DeviceOutcome<Unit>
}

/**
 * After-call observer. Receives the spec, total wall-clock duration in nanoseconds, and
 * the fault if the read failed (`null` on success). Observers must not throw — the
 * executor wraps invocations defensively, but a misbehaving observer that suspends
 * can still extend tail latency.
 *
 * Examples: structured logging, latency-budget counter, audit ledger.
 */
public fun interface ReadObserver {
    public suspend fun onRead(
        spec: DevicePropertyContract<*>,
        durationNanos: Long,
        fault: DeviceFault?,
    )
}

/**
 * Wraps the raw `delegate.reader(spec)` with a per-spec [TypedReader] decorator —
 * the typed-pipeline shape for value-substituting cross-cutting (caching, mock
 * injection, transformation). Decorators run in registration order before the
 * read enters the executor (`gates → locks → timeout(retry { decoratedReader })`).
 *
 * Unlike [ReadGate] (deny-only) and [ReadObserver] (after-call), a decorator may
 * substitute the read entirely — e.g. cache-hit short-circuits the I/O. The executor
 * applies the resulting reader under global timeout, after gates, with retry wrapping
 * only the lock-protected I/O attempt.
 */
public interface ReadDecorator {
    public fun <T> decorate(
        spec: DevicePropertyContract<T>,
        original: TypedReader<T>,
    ): TypedReader<T>
}

/**
 * Declarative QoS-style configuration for the read pipeline — the krig analogue
 * of DDS reader QoS policies. Per-property cross-cutting (timeout / retry / latency
 * budget / required locks) is sourced from each property's
 * `BehaviorAttribute`; this spec contributes the device-level shape:
 *
 *  - [gates]: fail-fast preconditions (RBAC, lifecycle, connection-state).
 *  - [decorators]: value-substituting wrappers around `reader(spec)` (caching, mocks).
 *  - [observers]: after-call sampling (logging, metrics, audit).
 *  - [defaultTimeout] / [defaultRetry] / [defaultLatencyBudget]: fallbacks applied when
 *    the property descriptor does not declare its own.
 *
 * Evolution policy: new cross-cutting concerns are added as additive `data class` fields
 * with safe defaults — non-breaking. The shape is intentionally closed; user-defined
 * cross-cutting must take the form of a [ReadGate], [ReadDecorator] or [ReadObserver].
 */
public data class ReadPipelineSpec(
    public val gates: List<ReadGate> = emptyList(),
    public val decorators: List<ReadDecorator> = emptyList(),
    public val observers: List<ReadObserver> = emptyList(),
    public val defaultTimeout: Duration? = null,
    public val defaultRetry: RetryPolicy? = null,
    public val defaultLatencyBudget: Duration? = null,
) {
    public companion object {
        /** Empty pipeline — no gates, decorators, observers, or defaults. For tests / debugging. */
        public val Empty: ReadPipelineSpec = ReadPipelineSpec()
    }
}
