package space.kscience.krig.dsl

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName
import space.kscience.krig.api.context.AnonymousPrincipal
import space.kscience.krig.api.context.Principal
import space.kscience.krig.api.context.executionContext
import space.kscience.krig.api.data.ObservedValue
import space.kscience.krig.api.data.toDataQuality
import space.kscience.krig.api.faults.OperationFault
import space.kscience.krig.api.lifecycle.LifecycleState
import space.kscience.krig.api.result.OperationOutcome
import space.kscience.krig.api.messages.FaultMessage
import space.kscience.krig.api.messages.PropertyChangedMessage
import space.kscience.krig.core.InternalKrigApi
import space.kscience.krig.core.contracts.Device
import space.kscience.krig.core.contracts.LifecycleStateHolder
import space.kscience.krig.core.contracts.SubscribeOptions
import space.kscience.krig.core.contracts.read
import space.kscience.krig.core.contracts.subscribe
import space.kscience.krig.core.contracts.typed.TypedSampler
import space.kscience.krig.core.meta.DevicePropertyContract
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

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

/** Property-granular variant: checks the per-property subscribe ACL (device-wide grant also passes). */
@Suppress("RETURN_VALUE_NOT_USED", "UnusedFlow")
public suspend fun Device.ensureAuthorized(principal: Principal, property: Name) {
    subscribe(principal, property)
}

// --- property flows ------------------------------------------------------

/** Filters the principal-gated message stream to [PropertyChangedMessage] for [propertyName]. */
public fun Device.propertyChangesFlow(
    principal: Principal,
    propertyName: Name,
    options: SubscribeOptions = SubscribeOptions.Unthrottled,
): Flow<PropertyChangedMessage> = flow {
    subscribe(principal, propertyName, options)
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

/** Typed `Flow<T>` via a [DevicePropertyContract]. */
public fun <T : Any> Device.typedPropertyFlow(
    principal: Principal,
    spec: DevicePropertyContract<T>,
    options: SubscribeOptions = SubscribeOptions.Unthrottled,
): Flow<T> = propertyChangesFlow(principal, spec.name, options).map { spec.converter.read(it.value) }

// --- config plane: live "current value + updates" state ------------------

/**
 * Authorised [StateFlow] of [spec]'s current value plus live updates — the "live config
 * field" view (the role coroutines [StateFlow] / controls-kt `DeviceState` play). Unlike
 * [typedPropertyFlow] (changes-only), this exposes the current value via [StateFlow.value]
 * and replays it to new collectors.
 *
 * Two source paths, one contract:
 *  - **Native** — if the driver overrides
 *    [propertyState][space.kscience.krig.core.contracts.typed.TypedDevice.propertyState] for
 *    [spec], its own state is returned after the subscribe ACL + audit run once. This is atomic
 *    and race-free; [scope] / [options] do not apply (the state is device-owned).
 *  - **Meta fallback** — otherwise the state is projected from [read] (seed) plus the
 *    principal-gated change stream, shared on [scope] (defaults to [Device.deviceScope]).
 *    Seeding is best-effort: a change racing the initial read converges on the next update.
 *    Drivers that need exact semantics override `propertyState`.
 *
 * In both paths reconfiguration still flows through [write] /
 * [writeProperty][space.kscience.krig.core.contracts.writeProperty]: every change is a
 * journaled, authorised control-plane event. This is a read-side projection — it never
 * bypasses the event journal nor affects deterministic replay.
 */
public suspend fun <T : Any> Device.typedPropertyState(
    principal: Principal,
    spec: DevicePropertyContract<T>,
    scope: CoroutineScope = deviceScope,
    options: SubscribeOptions = SubscribeOptions.Unthrottled,
): StateFlow<T> {
    propertyState(spec)?.let { native ->
        ensureAuthorized(principal, spec.name)
        return native
    }
    val initial = read(spec)
    return typedPropertyFlow(principal, spec, options).stateIn(scope, SharingStarted.Eagerly, initial)
}

/**
 * Quality-aware, **resilient** state of [spec]: current value plus live updates, each carrying its
 * timestamp and [ObservedValue.quality]. Unlike [typedPropertyState] (which needs a readable initial
 * value and surfaces a read failure as an exception), this never fails on construction — if the
 * initial read fails (e.g. a sensor is offline at start-up) the seed is `value = null` with the
 * fault's [quality][space.kscience.krig.api.data.DataQuality] (typically `BAD`/`UNCERTAIN`), so a UI
 * dashboard starts with a degraded indicator instead of crashing. Prefer this for control-room views
 * and watchdogs; use [typedPropertyState] when a non-null value is guaranteed.
 */
public suspend fun <T : Any> Device.observedPropertyState(
    principal: Principal,
    spec: DevicePropertyContract<T>,
    scope: CoroutineScope = deviceScope,
    options: SubscribeOptions = SubscribeOptions.Unthrottled,
): StateFlow<ObservedValue<T?>> {
    val initial: ObservedValue<T?> = when (val outcome = readObservedOutcome(spec.name)) {
        is OperationOutcome.Ok -> {
            val observed = outcome.value
            ObservedValue(observed.value?.let { spec.converter.read(it) }, observed.time, observed.quality)
        }
        is OperationOutcome.Fail -> ObservedValue(null, clock.now(), outcome.fault.toDataQuality())
    }
    return propertyChangesFlow(principal, spec.name, options)
        .map { msg -> ObservedValue(spec.converter.read(msg.value), msg.time, msg.quality) }
        .stateIn(scope, SharingStarted.Eagerly, initial)
}

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
        "SubscribeOptions.typeFilter selects wire message types and cannot be applied to typed sample values."
    }
    ensureAuthorized(principal, spec.name)
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

/** All faults from the control plane, carried by the unified [FaultMessage]. */
public fun Device.faultFlow(principal: Principal): Flow<OperationFault> = flow {
    subscribe(principal).collect { envelope ->
        val fault: OperationFault? = when (val msg = envelope.payload) {
            is FaultMessage -> msg.fault
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

/** Typed variant of [onPropertyChange] keyed by [DevicePropertyContract]. */
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

/** Typed variant of [onPropertyChangeFromContext] keyed by [DevicePropertyContract]. */
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
