package space.kscience.krig.api.data

import kotlin.time.Clock
import kotlin.time.Instant

/** A value produced or observed at a specific instant. */
public data class Timestamped<out T>(
    public override val value: T,
    public override val time: Instant,
) : Timed<T> {
    /** Maps [value] preserving [time]. */
    public inline fun <R> map(mapper: (T) -> R): Timestamped<R> =
        Timestamped(mapper(value), time)

    public companion object {
        /** Combines two timestamped values; resulting [time] is the later one. */
        public fun <T1, T2, R> combine(
            s1: Timestamped<T1>,
            s2: Timestamped<T2>,
            mapper: (T1, T2) -> R,
        ): Timestamped<R> = Timestamped(
            value = mapper(s1.value, s2.value),
            time = maxOf(s1.time, s2.time),
        )
    }
}

/** [value] timestamped by [clock]. */
public fun <T> timestamped(value: T, clock: Clock = Clock.System): Timestamped<T> =
    Timestamped(value, clock.now())

/** [timestamped] using the ambient `context(Clock)` — picks up virtual / compressed time. */
context(clock: Clock)
public fun <T> timestamped(value: T): Timestamped<T> =
    Timestamped(value = value, time = clock.now())
