package space.kscience.krig.core.operations

import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * A clock that runs at [factor] speed relative to the base clock.
 * factor = 10.0 means time runs 10x faster.
 */
public class CompressedClock(
    private val factor: Double,
    private val baseClock: Clock = Clock.System,
) : Clock {
    private val startReal: Instant = baseClock.now()
    private val startVirtual: Instant = startReal

    override fun now(): Instant {
        val realElapsed = baseClock.now() - startReal
        val virtualElapsed = (realElapsed.inWholeMilliseconds * factor).toLong().milliseconds
        return startVirtual + virtualElapsed
    }
}
