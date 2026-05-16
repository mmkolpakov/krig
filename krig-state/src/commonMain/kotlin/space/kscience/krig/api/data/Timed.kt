@file:MustUseReturnValues

package space.kscience.krig.api.data

import kotlin.time.Instant

/** Contract shared by carriers of a single value produced at an [Instant]. */
public interface Timed<out T> {
    public val value: T
    public val time: Instant
}

/** Tests whether [Timed.time] falls into the inclusive [range]. */
public fun <T> Timed<T>.inRange(range: ClosedRange<Instant>): Boolean = time in range
