package space.kscience.krig.core.operations

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import space.kscience.krig.api.data.DataQuality
import space.kscience.krig.api.data.ObservedValue
import space.kscience.krig.api.data.staleDataQuality
import kotlinx.coroutines.currentCoroutineContext
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** Time source for fixed-rate sampling: [markNow] anchors elapsed time, [delay] schedules the next tick. */
public interface SamplingClock {
    public fun markNow(): TimeMark
    public suspend fun delay(duration: Duration)
}

/** Monotonic production clock backed by [TimeSource.Monotonic] and `kotlinx.coroutines.delay`. */
public fun monotonicSamplingClock(): SamplingClock = object : SamplingClock {
    override fun markNow(): TimeMark = TimeSource.Monotonic.markNow()
    override suspend fun delay(duration: Duration) {
        kotlinx.coroutines.delay(duration)
    }
}

/**
 * Cold fixed-rate tick stream. Every collector owns its timer. Use [sharedTicks]
 * when several streams must sample from one timing source.
 */
public fun fixedRateTicks(
    tick: Duration,
    clock: SamplingClock = monotonicSamplingClock(),
): Flow<Unit> = flow {
    require(tick > Duration.ZERO) { "tick must be positive, got $tick" }
    val origin = clock.markNow()
    var nextTick = tick
    while (currentCoroutineContext().isActive) {
        val remaining = nextTick - origin.elapsedNow()
        if (remaining > Duration.ZERO) {
            clock.delay(remaining)
        }
        emit(Unit)

        val elapsed = origin.elapsedNow()
        do {
            nextTick += tick
        } while (nextTick <= elapsed)
    }
}

/**
 * Shared fixed-rate tick stream for multiple samplers or polling loops. Ticks are
 * live, non-replayed, conflated, and bound to [scope].
 */
public fun sharedTicks(
    scope: CoroutineScope,
    tick: Duration,
    clock: SamplingClock = monotonicSamplingClock(),
    started: SharingStarted = SharingStarted.WhileSubscribed(),
): SharedFlow<Unit> = fixedRateTicks(tick, clock)
    .buffer(Channel.CONFLATED)
    .shareIn(scope, started = started, replay = 0)

/**
 * Emits the last known value at every [tick], holding the previous value when no
 * new events arrive. Mirrors the ZOH (Zero-Order Hold) behaviour of industrial
 * control systems — unlike [kotlinx.coroutines.flow.sample] which skips empty
 * periods, this operator guarantees a value at every tick boundary.
 *
 * Reactive/control-plane helper. On JVM, `Flow<Double>` and this generic
 * implementation still cross boxed `T` boundaries; hard real-time or DSP-style
 * loops should read the primitive sampler hot path directly
 * (`RingDoubleSampler.latestDoubleOrNaN()` / `snapshotDoubleArray()`) instead of routing
 * every numeric sample through `Flow`.
 */
public fun <T> Flow<T>.sampleWithHold(
    tick: Duration,
    clock: SamplingClock = monotonicSamplingClock(),
): Flow<T> = channelFlow {
    require(tick > Duration.ZERO) { "tick must be positive, got $tick" }
    val latest = atomic<Any?>(UninitializedSample)
    val upstreamJob = launch(start = CoroutineStart.UNDISPATCHED) {
        this@sampleWithHold.collect { value ->
            latest.value = value
        }
    }
    val origin = clock.markNow()
    try {
        var nextTick = tick
        while (isActive && upstreamJob.isActive) {
            val remaining = nextTick - origin.elapsedNow()
            if (remaining > Duration.ZERO) {
                clock.delay(remaining)
            }
            if (!upstreamJob.isActive) break

            val snapshot = latest.value
            if (snapshot !== UninitializedSample) {
                @Suppress("UNCHECKED_CAST")
                trySend(snapshot as T)
            }

            val elapsed = origin.elapsedNow()
            do {
                nextTick += tick
            } while (nextTick <= elapsed)
        }
        upstreamJob.join()
    } finally {
        upstreamJob.cancel()
    }
}.buffer(Channel.RENDEZVOUS)

/**
 * Emits the last known upstream value whenever [ticks] emits. Use this overload
 * when many streams should share one timing source instead of allocating one
 * timer per stream.
 */
public fun <T> Flow<T>.sampleWithHold(ticks: Flow<Unit>): Flow<T> = channelFlow {
    val latest = atomic<Any?>(UninitializedSample)
    val upstreamJob = launch {
        this@sampleWithHold.collect { value ->
            latest.value = value
        }
        close()
    }
    try {
        ticks.collect {
            val snapshot = latest.value
            if (snapshot !== UninitializedSample) {
                @Suppress("UNCHECKED_CAST")
                send(snapshot as T)
            }
        }
    } finally {
        upstreamJob.cancel()
    }
}

/**
 * Emits one stale observation with the last known value when the upstream
 * observation stream completes or fails. Cancellation is propagated unchanged.
 */
public fun <T> Flow<ObservedValue<T>>.withStalenessFallback(
    clock: Clock = Clock.System,
    staleQuality: DataQuality = staleDataQuality(),
): Flow<ObservedValue<T>> = flow {
    var latest: Any? = UninitializedSample
    try {
        collect { observed ->
            latest = observed.value
            emit(observed)
        }
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        emitStale(latest, clock, staleQuality)
        throw e
    }
    emitStale(latest, clock, staleQuality)
}

private suspend fun <T> FlowCollector<ObservedValue<T>>.emitStale(
    latest: Any?,
    clock: Clock,
    staleQuality: DataQuality,
) {
    if (latest !== UninitializedSample) {
        @Suppress("UNCHECKED_CAST")
        emit(ObservedValue(latest as T, clock.now(), staleQuality))
    }
}

private object UninitializedSample
