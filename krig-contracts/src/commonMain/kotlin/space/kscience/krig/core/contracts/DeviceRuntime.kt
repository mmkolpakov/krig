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
)
