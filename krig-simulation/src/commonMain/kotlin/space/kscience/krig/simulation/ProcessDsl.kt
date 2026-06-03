package space.kscience.krig.simulation

import kotlinx.coroutines.*
import space.kscience.krig.concurrency.Resource
import space.kscience.krig.concurrency.ResourcePriority
import space.kscience.krig.concurrency.waitUntil
import space.kscience.krig.core.contracts.Device
import kotlin.coroutines.ContinuationInterceptor
import kotlin.time.Duration

/**
 * Process-oriented simulation DSL on `kotlinx.coroutines` + [DeterministicScheduler].
 * `hold` = `delay`, `waitUntil` = `StateFlow.waitUntil`, `request` = [Resource.use]. No
 * `sequence/yield` sentinels — plain `suspend fun` under structured concurrency.
 *
 * ```kotlin
 * simulationScope(scheduler).process("reactor") {
 *     hold(2.seconds)
 *     waitUntil(temperature) { it > 350.0 }
 *     request(coolant, amount = 50) { hold(30.seconds) }
 * }
 * ```
 */

/**
 * `@DslMarker` for the process DSL. Prevents the `hold` / `waitUntil` / `request` receiver of an
 * enclosing process from leaking into unrelated nested DSL blocks (compile-time scope isolation).
 */
@DslMarker
@Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE)
public annotation class SimulationDsl

/**
 * Marker receiver for process bodies. Scope-isolated via [SimulationDsl].
 */
@SimulationDsl
public interface ProcessScope : CoroutineScope

internal class ProcessScopeImpl(parent: CoroutineScope) : ProcessScope, CoroutineScope by parent

/**
 * Virtual-time simulation scope: a [CoroutineScope] running on a [SimulationScheduler]'s dispatcher
 * with the [scheduler] exposed for time advancement. Build it with [simulationScope].
 */
@SimulationDsl
public interface SimulationContext : CoroutineScope {
    public val scheduler: SimulationScheduler
}

internal class SimulationContextImpl(
    parent: CoroutineScope,
    override val scheduler: SimulationScheduler,
) : SimulationContext, CoroutineScope by parent

/**
 * Launches a named process on [this] scope. Returns the driving [Job]; join it, cancel it,
 * or supervise it like any other coroutine.
 *
 * The provided [block] runs in a [ProcessScope]; inside, [hold], [waitUntil], [request] and
 * StateFlow waiting work on the scope's dispatcher (typically [DeterministicScheduler]).
 */
public fun CoroutineScope.process(
    name: String = "process",
    block: suspend ProcessScope.() -> Unit,
): Job = launch(CoroutineName(name)) {
    block(ProcessScopeImpl(this))
}

/** Suspends the process for exactly [duration] of simulation time. Alias for [delay].
 * Must be called from a coroutine running on a virtual-time dispatcher.
 * Calling from a real-time dispatcher throws [IllegalStateException]. */
public suspend fun ProcessScope.hold(duration: Duration) {
    val dispatcher = coroutineContext[ContinuationInterceptor] as? CoroutineDispatcher
    check(dispatcher != null && DeterministicScheduler.isVirtualDispatcher(dispatcher)) {
        "hold() requires a virtual-time dispatcher (launched via simulationScope(...).process { ... } " +
        "or runTest { process { ... } }). Current dispatcher: ${dispatcher ?: "unknown"}"
    }
    delay(duration)
}

/**
 * Convenience overload: wait until an arbitrary `kotlinx.coroutines.flow.StateFlow` satisfies
 * a predicate. Useful with device state flows exposed by `DeviceState<T>`.
 */
@Suppress("UnusedReceiverParameter")
public suspend fun <T> ProcessScope.waitUntil(
    flow: kotlinx.coroutines.flow.StateFlow<T>,
    predicate: (T) -> Boolean,
): T = flow.waitUntil(predicate)

/**
 * Claims [amount] units of [resource], runs [block], and releases the units on exit
 * (even on exception). Wrapper over [Resource.use] — present so process bodies read
 * as a linear narrative.
 */
@Suppress("UnusedReceiverParameter")
public suspend fun <R> ProcessScope.request(
    resource: Resource,
    amount: Int = 1,
    priority: ResourcePriority = ResourcePriority.DEFAULT,
    block: suspend () -> R,
): R {
    return resource.use(amount, priority, block)
}

// --- device-side convenience --------------------------------------------

/**
 * Launches a process in the device's own [Device.deviceScope]. Shorthand for
 * `device.deviceScope.process(name, block)`.
 */
public fun Device.runProcess(
    name: String = "process",
    block: suspend ProcessScope.() -> Unit,
): Job = deviceScope.process(name, block)

/**
 * Creates a [SimulationContext] that uses [scheduler]'s dispatcher. Processes launched on
 * this scope advance virtual time via [SimulationScheduler.advanceBy]; no real clock
 * is consulted. The [scheduler][SimulationContext.scheduler] is exposed for time advancement.
 */
public fun simulationScope(scheduler: DeterministicScheduler): SimulationContext =
    SimulationContextImpl(CoroutineScope(scheduler.asDispatcher() + SupervisorJob()), scheduler)
