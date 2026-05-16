package space.kscience.krig.core.contracts

import kotlinx.coroutines.CoroutineScope
import space.kscience.dataforge.names.Name
import kotlin.time.Clock
import kotlin.time.TimeSource

/**
 * Minimal environment that a [DeviceBackend] needs to operate:
 * a wall/event [clock], a monotonic [timeSource] for elapsed-time measurements,
 * a coroutine scope, and the device's name.
 *
 * [Device] extends this interface, so a backend declared with
 * `context(env: DeviceEnvironment)` automatically receives the owning
 * device as its context parameter — no adapters needed.
 */
public interface DeviceEnvironment {
    public val clock: Clock

    /**
     * Monotonic source used for operation-duration measurements. This is intentionally
     * separate from [clock]: [Clock] answers "what event time is it?", while
     * [TimeSource] answers "how long did this operation take?".
     */
    public val timeSource: TimeSource
        get() = TimeSource.Monotonic

    public val deviceScope: CoroutineScope
    public val name: Name
}
