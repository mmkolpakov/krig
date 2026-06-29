package space.kscience.krig.core.contracts

import space.kscience.dataforge.context.Context
import space.kscience.dataforge.names.Name
import kotlin.time.Clock
import kotlin.time.TimeSource

/**
 * Per-operation execution environment a [DeviceBackend] needs: a wall/event [clock], a monotonic
 * [timeSource] for elapsed-time measurements, and the device's [name].
 *
 * [Device] extends this interface, so public device scopes and DSL read/write/action scopes can
 * expose operation time and device identity without inheriting the whole device lifecycle. Backend
 * drivers receive the richer [BackendEnvironment] after binding.
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

/**
 * Backend-lifetime view of a device runtime.
 *
 * A [Device] still owns the public execution environment and the device coroutine scope. A bound
 * backend receives only the services a driver operation needs: DataForge [context], event [clock],
 * monotonic [timeSource], and device [name]. It intentionally carries no `deviceScope`.
 */
public data class BackendEnvironment(
    public val context: Context,
    override val name: Name,
    override val clock: Clock = Clock.System,
    override val timeSource: TimeSource = TimeSource.Monotonic,
) : DeviceEnvironment {
    public companion object {
        public fun from(runtime: DeviceRuntime, name: Name): BackendEnvironment =
            BackendEnvironment(
                context = runtime.context,
                name = name,
                clock = runtime.clock,
                timeSource = runtime.timeSource,
            )
    }
}
