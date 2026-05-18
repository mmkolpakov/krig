package space.kscience.krig.api.data

import kotlin.time.Instant

/** A physical or protocol observation with data quality attached. */
public data class ObservedValue<out T>(
    public override val value: T,
    public override val time: Instant,
    public val quality: DataQuality,
) : Timed<T> {
    /** Maps the sample value preserving its timestamp and quality. */
    public inline fun <R> map(mapper: (T) -> R): ObservedValue<R> =
        ObservedValue(mapper(value), time, quality)

    public companion object {
        /** Combines two observed values; resulting time is the later one and quality is worst-wins. */
        public fun <T1, T2, R> combine(
            s1: ObservedValue<T1>,
            s2: ObservedValue<T2>,
            mapper: (T1, T2) -> R,
        ): ObservedValue<R> = ObservedValue(
            value = mapper(s1.value, s2.value),
            time = maxOf(s1.time, s2.time),
            quality = s1.quality.combine(s2.quality),
        )
    }
}

/** Creates an observation for the provided value using [clock] and [quality]. */
public fun <T> observed(
    value: T,
    clock: kotlin.time.Clock = kotlin.time.Clock.System,
    quality: DataQuality = DataQuality.GOOD,
): ObservedValue<T> = ObservedValue(value, clock.now(), quality)

/** [observed] using the ambient `context(Clock)` — picks up virtual / compressed time. */
context(clock: kotlin.time.Clock)
public fun <T> observed(
    value: T,
    quality: DataQuality = DataQuality.GOOD,
): ObservedValue<T> = ObservedValue(value = value, time = clock.now(), quality = quality)

/** `true` when the value has [DataQuality.GOOD] severity. */
public val ObservedValue<*>.isGood: Boolean
    get() = quality.severity == QualitySeverity.GOOD

/** `true` when the sample value is present and its quality is [DataQuality.GOOD]. */
public val ObservedValue<*>.isUsable: Boolean
    get() = value != null && isGood

/** Returns the sample value only when [isUsable], otherwise `null`. */
public val <T> ObservedValue<T>.usableValue: T?
    get() = if (isUsable) value else null

/** Returns [usableValue] or throws when the sample is absent or has degraded quality. */
public fun <T> ObservedValue<T>.requireUsableValue(): T =
    usableValue ?: error("Observed value is not usable: value=$value, quality=$quality")
