package space.kscience.controls.composite.simulation

import kotlinx.coroutines.*
//TODO think about utils module, not core for common things
import space.kscience.controls.core.serialization.instant
import space.kscience.dataforge.context.*
import space.kscience.dataforge.meta.*
import kotlin.coroutines.CoroutineContext
import kotlin.math.roundToLong
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

@OptIn(InternalCoroutinesApi::class)
private class CompressedTimeDispatcher(
    val coroutineContext: CoroutineContext,
    val compression: Double,
) : CoroutineDispatcher(), Delay {
    private val dispatcher = coroutineContext[CoroutineDispatcher] ?: Dispatchers.Default

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        dispatcher.dispatch(context, block)
    }

    private val parentDelay = ((dispatcher as? Delay) ?: (Dispatchers.Default as Delay))

    override fun scheduleResumeAfterDelay(timeMillis: Long, continuation: CancellableContinuation<Unit>) {
        parentDelay.scheduleResumeAfterDelay((timeMillis / compression).roundToLong(), continuation)
    }
}

private class CompressedClock(
    val baseClock: Clock = Clock.System,
    val compression: Double,
    val start: Instant = baseClock.now(),
) : Clock {
    override fun now(): Instant {
        val elapsed = (baseClock.now() - start)
        return start + elapsed / compression
    }
}

/**
 * Defines the operational mode for the [ClockManager].
 */
public sealed interface ClockMode {
    /** The manager will provide the system's real-time clock. */
    public data object System : ClockMode

    /** The manager will provide a custom, user-defined clock. */
    public data class Custom(public val clock: Clock) : ClockMode

    /** The manager will provide a clock that runs faster or slower than real-time by a given factor. */
    public data class Compressed(public val compression: Double) : ClockMode

    /** The manager will provide a virtual, manually-advanced clock and dispatcher for deterministic simulations. */
    public data class Virtual(public val scheduler: VirtualTimeDispatcher) : ClockMode
}

/**
 * A DataForge plugin that manages and provides a configurable [Clock] and `CoroutineDispatcher`.
 * This plugin is the core of the time simulation system, allowing a context to operate in
 * real-time, compressed time, or virtual time.
 *
 * The plugin is configured via its [Meta]. Example:
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

    /**
     * The configured clock mode, resolved lazily from the plugin's meta.
     */
    public val clockMode: ClockMode by lazy {
        when (meta["clock.mode"].string) {
            null, "system" -> ClockMode.System
            "virtual" -> ClockMode.Virtual(VirtualTimeDispatcher())
            "compressed" -> ClockMode.Compressed(meta["clock.compression"].double ?: 1.0)
            else -> error("Can't resolve custom clock for $meta")
        }
    }

    /**
     * The [Clock] instance provided by this manager.
     */
    public open val clock: Clock by lazy {
        when (val mode = clockMode) {
            ClockMode.System -> Clock.System
            is ClockMode.Custom -> mode.clock
            is ClockMode.Compressed -> CompressedClock(Clock.System, mode.compression)
            is ClockMode.Virtual -> mode.scheduler.asClock(meta["clock.start"]?.instant ?: Clock.System.now())
        }
    }

    /**
     * The [CoroutineDispatcher] provided by this manager, which respects the configured time mode.
     * For virtual time, this is the `VirtualTimeDispatcher`. For compressed time, it's a wrapper.
     * Otherwise, it's the context's default dispatcher.
     */
    public open val simulationDispatcher: CoroutineDispatcher by lazy {
        when (val mode = clockMode) {
            is ClockMode.System, is ClockMode.Custom ->
                context.coroutineContext[CoroutineDispatcher] ?: Dispatchers.Default
            is ClockMode.Compressed -> CompressedTimeDispatcher(
                coroutineContext = context.coroutineContext,
                compression = mode.compression
            )
            is ClockMode.Virtual -> mode.scheduler
        }
    }

    /**
     * Schedules a recurring task with a fixed delay, using the manager's `simulationDispatcher`.
     */
    public fun scheduleWithFixedDelay(tick: Duration, block: suspend () -> Unit): Job = context.launch(simulationDispatcher) {
        while (isActive) {
            delay(tick)
            block()
        }
    }

    override fun detach() {
        (clockMode as? ClockMode.Virtual)?.scheduler?.close()
        super.detach()
    }

    public companion object : PluginFactory<ClockManager> {
        override val tag: PluginTag = PluginTag("clock", group = PluginTag.DATAFORGE_GROUP)
        override fun build(context: Context, meta: Meta): ClockManager = ClockManager(Laminate(meta, context.properties))
    }
}
