package space.kscience.krig.dsl

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName
import space.kscience.krig.api.context.AnonymousPrincipal
import space.kscience.krig.api.context.Principal
import space.kscience.krig.api.context.executionContext
import space.kscience.krig.api.data.DataQuality
import space.kscience.krig.api.data.ObservedValue
import space.kscience.krig.api.data.QualityCode
import space.kscience.krig.api.data.QualitySeverity
import space.kscience.krig.api.faults.OperationFault
import space.kscience.krig.api.lifecycle.LifecycleState
import space.kscience.krig.api.messages.ActionFaultMessage
import space.kscience.krig.api.messages.DeviceErrorMessage
import space.kscience.krig.api.messages.PropertyChangedMessage
import space.kscience.krig.api.messages.PropertyFaultMessage
import space.kscience.krig.core.InternalKrigApi
import space.kscience.krig.core.contracts.Device
import space.kscience.krig.core.contracts.LifecycleStateHolder
import space.kscience.krig.core.contracts.SubscribeOptions
import space.kscience.krig.core.contracts.subscribe
import space.kscience.krig.core.contracts.typed.TypedSampler
import space.kscience.krig.core.meta.DevicePropertyContract
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Subscription sugar over principal-gated [Device.subscribe]. Authorisation runs once
 * per terminal operator; elements then stream with no per-element cost. Job-returning
 * helpers launch on [Device.deviceScope] by default.
 */

// --- authorization-only helper ------------------------------------------

/** Checks authorization for [principal] against the device's subscription gate.
 * Use when the caller only needs the auth side-effect (audit + permission check)
 * but not the message flow — e.g. before accessing a [TypedSampler] directly. */
@Suppress("RETURN_VALUE_NOT_USED", "UnusedFlow")
public suspend fun Device.ensureAuthorized(principal: Principal) {
    subscribe(principal) // auth + audit happen inside; flow is cold and never collected
}

// --- property flows ------------------------------------------------------

/** Filters the principal-gated message stream to [PropertyChangedMessage] for [propertyName]. */
public fun Device.propertyChangesFlow(
    principal: Principal,
    propertyName: Name,
    options: SubscribeOptions = SubscribeOptions.Unthrottled,
): Flow<PropertyChangedMessage> = flow {
    subscribe(principal, options)
        .map { it.payload }
        .filterIsInstance<PropertyChangedMessage>()
        .filter { it.property == propertyName }
        .collect { emit(it) }
}

/** `Flow<Meta>` of property values. */
public fun Device.propertyFlow(
    principal: Principal,
    propertyName: Name,
    options: SubscribeOptions = SubscribeOptions.Unthrottled,
): Flow<Meta> = propertyChangesFlow(principal, propertyName, options).map { it.value }

public fun Device.propertyFlow(
    principal: Principal,
    propertyName: String,
    options: SubscribeOptions = SubscribeOptions.Unthrottled,
): Flow<Meta> = propertyFlow(principal, propertyName.asName(), options)

/** Typed `Flow<T>` via a [DevicePropertySpec]. */
public fun <T : Any> Device.typedPropertyFlow(
    principal: Principal,
    spec: DevicePropertyContract<T>,
    options: SubscribeOptions = SubscribeOptions.Unthrottled,
): Flow<T> = propertyChangesFlow(principal, spec.name, options).map { spec.converter.read(it.value) }

/**
 * Typed data-plane samples for [spec]. If the driver exposes a [TypedSampler][space.kscience.krig.core.contracts.typed.TypedSampler],
 * this returns the sampler flow directly and does not touch [Meta] conversion. Otherwise it
 * falls back to [typedPropertyFlow].
 *
 * The returned flow is a live stream, not a history buffer: collectors observe values
 * published after subscription. Use [TypedSampler.latest] / [TypedSampler.snapshot]
 * for latest-value or bounded-buffer reads.
 */
public suspend fun <T : Any> Device.typedSamples(
    principal: Principal,
    spec: DevicePropertyContract<T>,
    options: SubscribeOptions = SubscribeOptions.Unthrottled,
): Flow<T> {
    val sampler = sampler(spec) ?: return typedPropertyFlow(principal, spec, options)

    require(options.typeFilter.isEmpty()) {
        "SubscribeOptions.typeFilter filters DeviceMessage classes and cannot be applied to typed sample values."
    }
    ensureAuthorized(principal)
    return sampler.flow().applyRateLimit(options)
}

@OptIn(FlowPreview::class)
private fun <T> Flow<T>.applyRateLimit(options: SubscribeOptions): Flow<T> {
    val hz = options.maxRateHz ?: return this
    if (options === SubscribeOptions.Unthrottled) return this
    require(hz > 0.0) { "maxRateHz must be positive, got $hz" }
    val period: Duration = (1.0 / hz).seconds
    return sample(period)
}

// --- fault & lifecycle flows --------------------------------------------

/** All faults from the control plane: [DeviceErrorMessage], [ActionFaultMessage], [PropertyFaultMessage]. */
public fun Device.faultFlow(principal: Principal): Flow<OperationFault> = flow {
    subscribe(principal).collect { envelope ->
        val fault: OperationFault? = when (val msg = envelope.payload) {
            is DeviceErrorMessage -> msg.failure.fault
            is ActionFaultMessage -> msg.fault
            is PropertyFaultMessage -> msg.fault
            else -> null
        }
        if (fault != null) emit(fault)
    }
}

/**
 * Stream of [LifecycleState] changes. Prefers [LifecycleStateHolder.lifecycleStateFlow];
 * falls back to a single emission of the current state for third-party `Device` impls.
 */
@OptIn(InternalKrigApi::class)
public fun Device.lifecycleFlow(principal: Principal): Flow<LifecycleState> = flow {
    ensureAuthorized(principal)
    val raw = (this@lifecycleFlow as? LifecycleStateHolder)?.lifecycleStateFlow
    if (raw != null) raw.collect { emit(it) } else emit(lifecycleState)
}

// --- Job-returning helpers ----------------------------------------------

/** Launches [action] on every new value at [name]; cancel the returned [Job] to stop. */
public fun Device.onPropertyChange(
    principal: Principal,
    name: Name,
    scope: CoroutineScope = deviceScope,
    action: suspend (Meta) -> Unit,
): Job = propertyFlow(principal, name).onEach(action).launchIn(scope)

/** Typed variant of [onPropertyChange] keyed by [DevicePropertySpec]. */
public fun <T : Any> Device.onPropertyChange(
    principal: Principal,
    spec: DevicePropertyContract<T>,
    scope: CoroutineScope = deviceScope,
    action: suspend (T) -> Unit,
): Job = typedPropertyFlow(principal, spec).onEach(action).launchIn(scope)

/** Runs [action] on every [OperationFault] surfaced by the control plane. */
public fun Device.onFault(
    principal: Principal,
    scope: CoroutineScope = deviceScope,
    action: suspend (OperationFault) -> Unit,
): Job = faultFlow(principal).onEach(action).launchIn(scope)

/** Runs [action] on every [LifecycleState] transition. */
public fun Device.onLifecycleChange(
    principal: Principal,
    scope: CoroutineScope = deviceScope,
    action: suspend (LifecycleState) -> Unit,
): Job = lifecycleFlow(principal).onEach(action).launchIn(scope)

// --- CoroutineContext shortcuts -----------------------------------------

private suspend fun currentPrincipal(): Principal =
    currentCoroutineContext().executionContext?.principal ?: AnonymousPrincipal

/** Launches [action] using the principal stored in the current coroutine context. */
public suspend fun Device.onPropertyChangeFromContext(
    name: Name,
    scope: CoroutineScope = deviceScope,
    action: suspend (Meta) -> Unit,
): Job = onPropertyChange(currentPrincipal(), name, scope, action)

/** Typed variant of [onPropertyChangeFromContext] keyed by [DevicePropertySpec]. */
public suspend fun <T : Any> Device.onPropertyChangeFromContext(
    spec: DevicePropertyContract<T>,
    scope: CoroutineScope = deviceScope,
    action: suspend (T) -> Unit,
): Job = onPropertyChange(currentPrincipal(), spec, scope, action)

/** Runs [action] on faults using the principal stored in the current coroutine context. */
public suspend fun Device.onFaultFromContext(
    scope: CoroutineScope = deviceScope,
    action: suspend (OperationFault) -> Unit,
): Job = onFault(currentPrincipal(), scope, action)

/** Runs [action] on lifecycle changes using the principal stored in the current coroutine context. */
public suspend fun Device.onLifecycleChangeFromContext(
    scope: CoroutineScope = deviceScope,
    action: suspend (LifecycleState) -> Unit,
): Job = onLifecycleChange(currentPrincipal(), scope, action)

// --- Zero-Order Hold --------------------------------------------------------

/** Time source used by [sampleWithHold]. */
public interface SamplingClock {
    public fun markNow(): TimeMark
    public suspend fun delay(duration: Duration)
}

/** Monotonic production clock for [sampleWithHold]. */
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
 * This is a reactive/control-plane helper. On JVM, `Flow<Double>` and this
 * generic implementation still cross boxed `T` boundaries; hard real-time or
 * DSP-style loops should read the primitive sampler hot path directly
 * (`DoubleSampler.latestDoubleOrNaN()` / `snapshotDoubleArray()`) instead of routing
 * every numeric sample through `Flow`.
 *
 * ```kotlin
 * device.typedSamples(principal, spec)
 *     .sampleWithHold(100.milliseconds)
 *     .collect { v -> updateChart(v) }
 * ```
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
    staleQuality: DataQuality = DataQuality(
        severity = QualitySeverity.BAD,
        code = QualityCode("krig.stale"),
    ),
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
