package space.kscience.controls.composite.dsl.guards

import kotlinx.serialization.serializer
import ru.nsk.kstatemachine.event.Event
import space.kscience.controls.composite.dsl.CompositeSpecBuilder
import space.kscience.controls.core.legacy_alpha_2.contracts.Device
import space.kscience.controls.fsm.guards.OperationalGuardsFeature
import space.kscience.controls.validation.TimedPredicateGuardSpec
import space.kscience.controls.core.legacy_alpha_2.meta.DevicePropertySpec
import space.kscience.controls.api.descriptors.PropertyKind
import space.kscience.controls.core.features.GuardSpec
import space.kscience.controls.fsm.guards.ValueChangeGuardSpec
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.MutableMeta
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import kotlin.time.Duration

/**
 * An intermediate DSL context for a timed predicate guard, holding the predicate and the required hold duration.
 * This context is the final step before posting an event via the [post] function.
 *
 * @property builder The parent [GuardsBuilder] instance, providing access to the list of guards.
 * @property predicate The boolean [DevicePropertySpec] that this guard monitors.
 * @property duration The minimum [Duration] for which the predicate must remain `true` to trigger.
 */
public class TimedGuardContext(
    public val builder: GuardsBuilder,
    public val predicate: DevicePropertySpec<*, Boolean>,
    public val duration: Duration,
)

/**
 * An intermediate DSL context for a timed predicate guard, holding the predicate property.
 * This context is returned by [GuardsBuilder.whenTrue] and is used to specify the hold duration via [forAtLeast].
 *
 * @property builder The parent [GuardsBuilder] instance.
 * @property predicate The boolean [DevicePropertySpec] that this guard monitors.
 */
public class GuardContext(
    public val builder: GuardsBuilder,
    public val predicate: DevicePropertySpec<*, Boolean>,
) {
    /**
     * Specifies the minimum duration for which the predicate must hold `true` to trigger the guard.
     * @param duration The hold duration.
     * @return A [TimedGuardContext] to continue the DSL chain.
     */
    public infix fun forAtLeast(duration: Duration): TimedGuardContext {
        return TimedGuardContext(builder, predicate, duration)
    }
}

/**
 * An intermediate DSL context for a stateful guard, holding all necessary information before the final event posting.
 *
 * @property builder The parent [GuardsBuilder] instance.
 * @property property The numeric property being monitored.
 * @property windowSize The number of historical values to consider.
 * @property predicateId The ID of the [space.kscience.controls.fsm.guards.HistoricalPredicate] logic to apply.
 * @property predicateMeta Configuration metadata for the historical predicate.
 */
public class FulfillsGuardContext(
    @PublishedApi internal val builder: GuardsBuilder,
    @PublishedApi internal val property: DevicePropertySpec<*, out Number>,
    @PublishedApi internal val windowSize: Int,
    @PublishedApi internal val predicateId: String,
    @PublishedApi internal val predicateMeta: Meta,
) {
    /**
     * Completes the stateful guard definition by specifying the [Event] to be posted when the historical predicate is fulfilled.
     * This function creates a [space.kscience.controls.fsm.guards.ValueChangeGuardSpec] and registers it.
     *
     * @param E The type of the event to post, must be `@Serializable`.
     * @param onlyInStates An optional set of state names. If provided, the guard is only active when the
     *                     operational FSM is in one of these states.
     * @param eventMetaBuilder A DSL block to configure the metadata of the event to be posted.
     */
    public inline fun <reified E : Event> post(
        onlyInStates: Set<String> = emptySet(),
        crossinline eventMetaBuilder: MutableMeta.() -> Unit = {},
    ) {
        val serialName = serializer<E>().descriptor.serialName
        val eventMeta = Meta(eventMetaBuilder)
        val guard = ValueChangeGuardSpec(
            propertyName = this.property.name,
            windowSize = this.windowSize,
            predicateId = this.predicateId,
            predicateMeta = this.predicateMeta,
            postEventSerialName = serialName,
            eventMeta = eventMeta,
            onlyInStates = onlyInStates
        )
        builder.guards.add(guard)
    }
}

/**
 * An intermediate DSL context for a stateful guard that holds the property and the history window size.
 *
 * @property builder The parent [GuardsBuilder] instance.
 * @property property The numeric property being monitored.
 * @property windowSize The number of historical values to consider.
 */
public class WindowedGuardContext(
    @PublishedApi internal val builder: GuardsBuilder,
    @PublishedApi internal val property: DevicePropertySpec<*, out Number>,
    @PublishedApi internal val windowSize: Int,
) {
    /**
     * Specifies the `HistoricalPredicate` logic to be applied to the window of historical values.
     *
     * @param predicateId The unique string identifier of the [space.kscience.controls.fsm.guards.HistoricalPredicate] logic, which will be resolved by the runtime.
     * @param predicateMeta Optional configuration metadata for the predicate logic.
     * @return A [FulfillsGuardContext] to continue the DSL chain.
     */
    public fun fulfills(predicateId: String, predicateMeta: Meta = Meta.EMPTY): FulfillsGuardContext =
        FulfillsGuardContext(builder, property, windowSize, predicateId, predicateMeta)
}


/**
 * An intermediate DSL context for a stateful guard, holding the numeric property to be monitored.
 *
 * @property builder The parent [GuardsBuilder] instance.
 * @property property The numeric property being monitored.
 */
public class StatisticalGuardContext(
    @PublishedApi internal val builder: GuardsBuilder,
    @PublishedApi internal val property: DevicePropertySpec<*, out Number>,
) {
    /**
     * Specifies the size of the history window (number of values) to be passed to the predicate.
     * @param windowSize The number of historical values to track.
     * @return A [WindowedGuardContext] to continue the DSL chain.
     */
    public infix fun over(windowSize: Int): WindowedGuardContext =
        WindowedGuardContext(builder, property, windowSize)
}

/**
 * A DSL builder for defining a collection of operational guards for a device.
 * This builder is accessed via the [CompositeSpecBuilder.guards] block.
 *
 * @property specBuilder The parent [CompositeSpecBuilder], exposed for potential advanced integrations.
 */
public class GuardsBuilder(
    @PublishedApi
    internal val specBuilder: CompositeSpecBuilder<*>,
) {
    /**
     * The internal, mutable list of [space.kscience.controls.core.features.GuardSpec] instances being built.
     * Marked as `@PublishedApi` to be accessible from `inline` functions.
     */
    @PublishedApi
    internal val guards: MutableList<GuardSpec> = mutableListOf()

    /**
     * Starts the definition of a "timed predicate guard". This guard monitors a boolean property and triggers
     * an event if the property remains `true` for a specified duration.
     *
     * @param predicate The boolean property to monitor. It must be of `PropertyKind.PREDICATE`.
     * @return A [GuardContext] to specify the duration via `forAtLeast`.
     */
    public fun whenTrue(predicate: DevicePropertySpec<*, Boolean>): GuardContext {
        require(predicate.descriptor.kind == PropertyKind.PREDICATE) {
            "The property '${predicate.name}' used in a guard must be a PREDICATE."
        }
        return GuardContext(this, predicate)
    }

    /**
     * Starts the definition of a "stateful" or "statistical guard". This guard monitors the history of a
     * numeric property and triggers an event based on a custom predicate logic applied to that history.
     *
     * @param property The numeric property to monitor.
     * @return A [StatisticalGuardContext] to specify the history window size via `over`.
     */
    public fun whenValue(property: DevicePropertySpec<*, out Number>): StatisticalGuardContext =
        StatisticalGuardContext(this, property)
}

/**
 * Completes the timed predicate guard definition by specifying the [Event] to be posted.
 * This function creates a [TimedPredicateGuardSpec] and registers it.
 *
 * @param E The type of the event to post, must be `@Serializable`.
 * @param onlyInStates An optional set of state names. If provided, the guard is only active when the
 *                     operational FSM is in one of these states.
 * @param eventMetaBuilder A DSL block to configure the metadata of the event to be posted.
 */
public inline fun <reified E : Event> TimedGuardContext.post(
    onlyInStates: Set<String> = emptySet(),
    crossinline eventMetaBuilder: MutableMeta.() -> Unit = {},
) {
    val serialName = serializer<E>().descriptor.serialName
    val eventMeta = Meta(eventMetaBuilder)
    val guard = TimedPredicateGuardSpec(
        predicateName = this.predicate.name,
        holdFor = this.duration,
        postEventSerialName = serialName,
        eventMeta = eventMeta,
        onlyInStates = onlyInStates
    )
    builder.guards.add(guard)
}

/**
 * Defines a set of operational guards for a device. This is the main entry point for the guards DSL.
 *
 * This function automatically performs two crucial integration steps:
 * 1. It adds an [OperationalGuardsFeature] to the blueprint, making the device's use of guards explicit and discoverable.
 * 2. It collects all event types posted by the guards and adds them to the [space.kscience.controls.fsm.OperationalFsmFeature], ensuring that
 *    the blueprint's static FSM old is complete and can be validated.
 *
 * @param D The type of the device contract.
 * @param block A lambda with a [GuardsBuilder] receiver where guards are defined.
 */
public fun <D : Device> CompositeSpecBuilder<D>.guards(block: GuardsBuilder.() -> Unit) {
    contract {
        callsInPlace(block, InvocationKind.EXACTLY_ONCE)
    }
    val builder = GuardsBuilder(this).apply(block)
    if (builder.guards.isNotEmpty()) {
        feature(OperationalGuardsFeature(builder.guards))
    }
}