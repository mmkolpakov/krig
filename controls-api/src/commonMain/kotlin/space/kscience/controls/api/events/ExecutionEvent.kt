package space.kscience.controls.api.events

import kotlinx.serialization.Polymorphic
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import space.kscience.controls.api.addressing.Address
import space.kscience.controls.api.identifiers.CorrelationId
import space.kscience.controls.api.faults.DeviceFault
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.Name
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * An interface for telemetry events that describe the *process* of an operation's execution.
 * These events are intended for observability, monitoring, and tracing systems (like OpenTelemetry).
 *
 * All execution events are linked by a [correlationId] to trace a single logical operation.
 *
 * @property time The high-precision timestamp when the event occurred.
 * @property sourceDevice The address of the device where the event originated.
 * @property action The name of the action associated with this event.
 * @property correlationId A unique identifier to trace a single logical operation.
 * @property attributes Arbitrary key-value pairs providing additional context for this event.
 *                      This corresponds to "attributes" on an OpenTelemetry Span.
 */
@Polymorphic
public interface ExecutionEvent {
    public val time: Instant
    public val sourceDevice: Address
    public val action: Name
    public val correlationId: CorrelationId?
    public val attributes: Meta
}

/**
 * An event fired when a request to execute an action is first received and dispatched by the hub.
 *
 * @param input The input [Meta] provided for the action.
 */
@Serializable
@SerialName("telemetry.action.dispatched")
public data class ActionDispatched(
    override val time: Instant,
    override val sourceDevice: Address,
    override val action: Name,
    public val input: Meta?,
    override val correlationId: CorrelationId?,
    override val attributes: Meta = Meta.EMPTY,
) : ExecutionEvent

/**
 * An event fired just before the core logic of an action is executed.
 */
@Serializable
@SerialName("telemetry.action.started")
public data class ActionStarted(
    override val time: Instant,
    override val sourceDevice: Address,
    override val action: Name,
    override val correlationId: CorrelationId?,
    override val attributes: Meta = Meta.EMPTY,
) : ExecutionEvent

/**
 * An event fired immediately after the core logic of an action has successfully completed.
 *
 * @param duration The execution time of the action's logic.
 */
@Serializable
@SerialName("telemetry.action.completed")
public data class ActionCompleted(
    override val time: Instant,
    override val sourceDevice: Address,
    override val action: Name,
    val duration: Duration,
    override val correlationId: CorrelationId?,
    override val attributes: Meta = Meta.EMPTY,
) : ExecutionEvent

/**
 * An event fired when a cache lookup for an action's result is successful.
 *
 * @param cacheKey The canonical key used for the cache lookup.
 */
@Serializable
@SerialName("telemetry.cache.hit")
public data class CacheHit(
    override val time: Instant,
    override val sourceDevice: Address,
    override val action: Name,
    val cacheKey: String,
    override val correlationId: CorrelationId?,
    override val attributes: Meta = Meta.EMPTY,
) : ExecutionEvent

/**
 * An event fired when a cache lookup for an action's result fails.
 *
 * @param cacheKey The canonical key used for the cache lookup.
 */
@Serializable
@SerialName("telemetry.cache.miss")
public data class CacheMiss(
    override val time: Instant,
    override val sourceDevice: Address,
    override val action: Name,
    val cacheKey: String,
    override val correlationId: CorrelationId?,
    override val attributes: Meta = Meta.EMPTY,
) : ExecutionEvent

/**
 * An event fired when an action completes with a predictable business fault.
 *
 * @param fault The structured [DeviceFault] object describing the business error.
 */
@Serializable
@SerialName("telemetry.fault.reported")
public data class FaultReported(
    override val time: Instant,
    override val sourceDevice: Address,
    override val action: Name,
    val fault: DeviceFault,
    override val correlationId: CorrelationId?,
    override val attributes: Meta = Meta.EMPTY,
) : ExecutionEvent