package space.kscience.krig.core.contracts

import space.kscience.krig.core.InternalKrigApi
import space.kscience.krig.core.operations.HybridLogicalClock
import space.kscience.dataforge.context.Context
import kotlin.time.Clock
import kotlin.time.TimeSource

/**
 * Construction-time parameters for [AbstractDevice]: DataForge [context], a [clock] (real
 * or virtual), the two-plane [messaging] policy, an optional distributed [hlc], and a
 * monotonic [timeSource] for duration measurements.
 */
@OptIn(InternalKrigApi::class)
public data class DeviceRuntime(
    public val context: Context,
    public val clock: Clock = Clock.System,
    public val messaging: DeviceMessaging = DeviceMessaging.Default,
    public val hlc: HybridLogicalClock? = null,
    public val timeSource: TimeSource = TimeSource.Monotonic,
) {
    public companion object {
        /**
         * Builds a [DeviceRuntime] whose [clock]/[timeSource] follow a context [RuntimeClockSource]
         * (the simulation `ClockManager` plugin), falling back to [Clock.System]/[TimeSource.Monotonic]
         * when none is installed. This is the single wiring point that keeps wall-clock time aligned
         * with virtual/compressed simulation time.
         *
         * Single-node semantics are preserved: [hlc] stays `null` unless [distributed] is `true`, in
         * which case the HLC's physical component is the resolved [clock] — so HLC stamps in distributed
         * simulations follow virtual time instead of drifting to wall-clock.
         */
        public fun from(
            context: Context,
            messaging: DeviceMessaging? = null,
            distributed: Boolean = false,
        ): DeviceRuntime {
            val clockSource = context.plugins.filterIsInstance<RuntimeClockSource>().firstOrNull()
            val clock = clockSource?.clock ?: Clock.System
            val timeSource = clockSource?.timeSource ?: TimeSource.Monotonic
            return DeviceRuntime(
                context = context,
                clock = clock,
                messaging = messaging ?: DeviceMessaging.resolve(context.properties),
                hlc = if (distributed) HybridLogicalClock(physicalClock = clock) else null,
                timeSource = timeSource,
            )
        }
    }
}

/**
 * Context-level source of simulation time. Implemented by the simulation `ClockManager` plugin so
 * [DeviceRuntime.from] can pick up virtual/compressed time without krig-contracts depending on
 * krig-simulation. Resolved generically from `context.plugins`.
 */
@InternalKrigApi
public interface RuntimeClockSource {
    public val clock: Clock
    public val timeSource: TimeSource
}
