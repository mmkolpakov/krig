package space.kscience.krig.core.contracts

import space.kscience.dataforge.names.Name
import kotlin.time.Clock
import kotlin.time.TimeSource

/**
 * Per-operation execution environment a [DeviceBackend] needs: a wall/event [clock], a monotonic
 * [timeSource] for elapsed-time measurements, and the device's [name].
 *
 * [Device] extends this interface, so backend operations declared with
 * `context(env: DeviceEnvironment)` receive the current operation environment without manual
 * parameter plumbing. It is not a replacement for DataForge Context services.
 *
 * By design it carries **no** `deviceScope`: a single operation environment — and the DSL
 * read/write/action scopes that derive from it — must not be able to `launch`/`async` into the
 * device's lifetime. The owning [Device] exposes `deviceScope` directly for code that legitimately
 * manages device-lifetime coroutines (hubs, subscriptions, capabilities). This makes the leak a
 * compile-time impossibility rather than a DSL convention.
 */
public interface DeviceEnvironment {
    public val clock: Clock

    /**
     * Monotonic source used for operation-duration measurements.
     *
     * Keep this separate from [clock]: [Clock] answers "what event time is it?",
     * while [TimeSource] answers "how long did this operation take?".
     */
    public val timeSource: TimeSource
        get() = TimeSource.Monotonic

    public val name: Name
}
