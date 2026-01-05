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
