package space.kscience.controls.api.spec

import kotlinx.serialization.Polymorphic
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import space.kscience.controls.common.meta.serializableToMeta
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.MetaRepr
import kotlin.time.Duration

/**
 * A polymorphic interface for declarative rules that govern data flow from the Fast Path (Atomic)
 * to the Slow Path (Events/DataForge).
 *
 * Telemetry policies allow the [space.kscience.controls.core.device.PropertyRegistry] to manage
 * the load on the message bus and UI by throttling or filtering updates at the source.
 */
@Polymorphic
public interface TelemetryPolicy : MetaRepr {
    override fun toMeta(): Meta = serializableToMeta(serializer(), this)
}

/**
 * Standard implementation for real-time data flow.
 * Every change in the physical state results in an immediate event.
 * Use this for critical alarms or low-frequency properties.
 */
@Serializable
@SerialName("telemetry.realtime")
public object RealtimePolicy : TelemetryPolicy

/**
 * Throttles updates to a fixed interval.
 * Only the latest value is emitted once per [interval].
 *
 * @property interval The minimum duration between two consecutive telemetry events.
 */
@Serializable
@SerialName("telemetry.sampled")
public data class SampledPolicy(val interval: Duration) : TelemetryPolicy

/**
 * Emits a telemetry event only if the absolute change in value exceeds a given [delta].
 * This is also known as "Deadband" filtering.
 *
 * @property delta The minimum threshold of change required to trigger an update.
 */
@Serializable
@SerialName("telemetry.deadband")
public data class DeadbandPolicy(val delta: Double) : TelemetryPolicy

/**
 * Emit only the latest value if multiple updates occur faster than consumers can process.
 * (Conceptually similar to Sampled, but driven by backpressure).
 */
@Serializable
@SerialName("policy.conflated")
public object ConflatedPolicy : TelemetryPolicy