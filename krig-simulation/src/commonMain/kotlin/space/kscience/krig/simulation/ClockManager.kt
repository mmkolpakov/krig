package space.kscience.krig.simulation

import kotlinx.coroutines.*
import space.kscience.krig.core.operations.CompressedClock
import space.kscience.dataforge.context.AbstractPlugin
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.context.PluginFactory
import space.kscience.dataforge.context.PluginTag
import space.kscience.dataforge.meta.*
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.math.roundToLong
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.TimeSource

@OptIn(InternalCoroutinesApi::class)
private class CompressedTimeDispatcher(
    val coroutineContext: CoroutineContext,
    val compression: Double,
) : CoroutineDispatcher(), Delay {
    private val dispatcher = (coroutineContext[ContinuationInterceptor] as? CoroutineDispatcher) ?: Dispatchers.Default

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        dispatcher.dispatch(context, block)
    }

    private val parentDelay = ((dispatcher as? Delay) ?: (Dispatchers.Default as Delay))

    /**
     * Compresses delays by [compression] but **clamps to 1 ms** to avoid scheduling
     * `delay(0)` (effectively `yield()`), which collapses simulation time when the
     * compressed value rounds below 1. OS timer resolution (Windows ~15 ms,
     * Linux ~1 ms HRT, JS 4 ms HTML5 throttle) makes sub-ms scheduling unreliable
     * regardless. For deterministic sub-ms simulations use [ClockMode.Virtual].
     */
    override fun scheduleResumeAfterDelay(timeMillis: Long, continuation: CancellableContinuation<Unit>) {
        val scaled = (timeMillis / compression).roundToLong().coerceAtLeast(1L)
        parentDelay.scheduleResumeAfterDelay(scaled, continuation)
    }
}

/** Clock strategy for a [ClockManager]. */
public sealed interface ClockMode {
    public data object System : ClockMode

    public data class Custom(public val clock: Clock) : ClockMode

    /**
     * Real-time scaled by [compression] (>1 = faster, <1 = slower).
     */
    public data class Compressed(public val compression: Double) : ClockMode

    /** Manually-advanced virtual clock backed by a [SimulationScheduler]. */
    public data class Virtual(
        public val scheduler: SimulationScheduler,
    ) : ClockMode
}

/**
 * DataForge plugin providing a [Clock] and a matching `CoroutineDispatcher`, configured
 * via [Meta] — real-time, compressed, or virtual.
 *
 * ```
 * plugin(ClockManager) {
 *     put("clock") {
 *         "mode" put "virtual"
 *         "start" put "2025-01-01T00:00:00Z"
 *     }
 * }
 * ```
 */
public open class ClockManager(meta: Meta) : AbstractPlugin(meta) {
    override val tag: PluginTag get() = Companion.tag

    @OptIn(ExperimentalCoroutinesApi::class)
    public val clockMode: ClockMode by lazy {
        when (meta["clock.mode"].string) {
            null, "system" -> ClockMode.System
            "virtual" -> ClockMode.Virtual(DeterministicScheduler())
            "compressed" -> ClockMode.Compressed(meta["clock.compression"].double ?: 1.0)
            else -> error("Can't resolve custom clock for $meta")
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    public open val clock: Clock by lazy {
        when (val mode = clockMode) {
            ClockMode.System -> Clock.System
            is ClockMode.Custom -> mode.clock
            is ClockMode.Compressed -> CompressedClock(factor = mode.compression, baseClock = Clock.System)
            is ClockMode.Virtual -> mode.scheduler.asClock()
        }
    }

    /** Monotonic source paired with [clock] for elapsed-duration measurements. */
    @OptIn(ExperimentalCoroutinesApi::class)
    public open val timeSource: TimeSource by lazy {
        when (val mode = clockMode) {
            is ClockMode.Virtual -> mode.scheduler.asTimeSource()
            else -> TimeSource.Monotonic
        }
    }

    /** Dispatcher honouring [clockMode]: virtual for [ClockMode.Virtual], scaled for [ClockMode.Compressed]. */
    @OptIn(ExperimentalCoroutinesApi::class)
    public open val simulationDispatcher: CoroutineDispatcher by lazy {
        when (val mode = clockMode) {
            is ClockMode.System, is ClockMode.Custom ->
                (context.coroutineContext[ContinuationInterceptor] as? CoroutineDispatcher) ?: Dispatchers.Default
            is ClockMode.Compressed -> CompressedTimeDispatcher(
                coroutineContext = context.coroutineContext,
                compression = mode.compression
            )
            is ClockMode.Virtual -> mode.scheduler.asDispatcher()
        }
    }

    /** Fixed-delay recurring task scheduled on [simulationDispatcher]. */
    public fun scheduleWithFixedDelay(tick: Duration, block: suspend () -> Unit): Job = context.launch(simulationDispatcher) {
        while (isActive) {
            delay(tick)
            block()
        }
    }

    public companion object : PluginFactory<ClockManager> {
        override val tag: PluginTag = PluginTag("clock", group = PluginTag.DATAFORGE_GROUP)
        override fun build(context: Context, meta: Meta): ClockManager = ClockManager(Laminate(meta, context.properties))
    }
}
