package space.kscience.krig.simulation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import space.kscience.krig.core.contracts.DeviceRuntime
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.context.ContextBuilder
import space.kscience.dataforge.context.PluginFactory
import space.kscience.dataforge.context.PluginTag
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.asName
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.time.TimeSource

/**
 * Configures the context to use a virtual timeline, starting at the given [start] time.
 * All `delay` calls and `Clock` access within this context are routed through the
 * [ClockManager.simulationDispatcher] and [ClockManager.clock].
 *
 * @param start The initial [Instant] for the virtual timeline. Defaults to the current system time.
 */
public fun ContextBuilder.withVirtualTime(start: Instant = Clock.System.now()) {
    plugin(ClockManager) {
        set("clock".asName(), Meta {
            "mode" put "virtual"
            "start" put start.toString()
        })
    }
}

/**
 * Configures the context to use a compressed or expanded timescale.
 * `delay` calls will be scaled by the given [compression] factor. For example, a compression
 * factor of `10.0` will make `delay(1.seconds)` complete in 100 milliseconds of real time.
 *
 * @param compression The time compression factor. Values > 1.0 speed up time; values < 1.0 slow it down.
 * @throws IllegalArgumentException if compression is not positive.
 */
public fun ContextBuilder.withTimeCompression(compression: Double) {
    require(compression > 0.0) { "Time compression must be a positive number." }
    plugin(ClockManager) {
        set("clock".asName(), Meta {
            "mode" put "compressed"
            "compression" put compression
        })
    }
}

/**
 * Bundled outputs of [withVirtualTime]: a Context whose [Clock] is driven by
 * [scheduler], plus a ready-to-use [scope] bound to the scheduler's dispatcher.
 */
public data class VirtualTimeScope(
    public val context: Context,
    public val scheduler: DeterministicScheduler,
    public val scope: CoroutineScope,
) {
    public val runtime: DeviceRuntime =
        DeviceRuntime(context = context, clock = scheduler.asClock(), timeSource = scheduler.asTimeSource())
}

/**
 * One-call simulation setup: creates a [DeterministicScheduler], wires it into a fresh
 * context via [ClockManager]'s virtual mode, and returns a scope running on the
 * scheduler's dispatcher.
 *
 * ```kotlin
 * val (ctx, scheduler, scope) = Global.withVirtualTime()
 * val thermo = scope.async { device("thermo", context = ctx) { … } }
 * scheduler.advanceBy(1.seconds)
 * ```
 */
public fun Context.withVirtualTime(start: Instant = Instant.fromEpochMilliseconds(0L)): VirtualTimeScope {
    val scheduler = DeterministicScheduler(initialTimeMs = start.toEpochMilliseconds())
    val context = Context(name = "virtual-${name}") {
        plugin(VirtualClockManagerFactory(scheduler))
    }
    val scope = CoroutineScope(scheduler.asDispatcher() + SupervisorJob())
    return VirtualTimeScope(context, scheduler, scope)
}

/** Factory for a [ClockManager] already wired to [scheduler] — skips Meta-based mode resolution. */
private class VirtualClockManagerFactory(
    private val scheduler: DeterministicScheduler,
) : PluginFactory<ClockManager> {
    override val tag: PluginTag get() = ClockManager.tag
    override fun build(context: Context, meta: Meta): ClockManager =
        object : ClockManager(meta) {
            override val clock: Clock get() = scheduler.asClock()
            override val timeSource: TimeSource get() = scheduler.asTimeSource()
            override val simulationDispatcher: CoroutineDispatcher get() = scheduler.asDispatcher()
        }
}
