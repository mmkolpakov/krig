package space.kscience.controls.telemetry

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import space.kscience.controls.api.addressing.Address
import space.kscience.controls.api.data.DataQuality
import space.kscience.controls.api.data.RawValue
import space.kscience.controls.api.identifiers.CorrelationId
import space.kscience.controls.api.messages.DeviceMessage
import space.kscience.dataforge.names.Name
import kotlin.time.Instant

/**
 * A single update for a specific tag/property within a telemetry packet.
 * To reduce network overhead in high-frequency streams, [alias] can be used instead of [property].
 */
@Serializable
public data class TagUpdate(
    val property: Name? = null,
    val alias: Int? = null,
    val value: RawValue,
    val quality: DataQuality,
    val timestamp: Instant
) {
    init {
        require(property != null || alias != null) { "TagUpdate must contain either a property name or an alias." }
    }
}

/**
 * A high-performance packet containing multiple property updates.
 * This message type bypasses the heavy `Meta` structures and uses `RawValue` for the Data Plane.
 */
@Serializable
@SerialName("telemetry")
public data class TelemetryPacket(
    override val sourceDevice: Address,
    val updates: List<TagUpdate>,
    override val time: Instant,
    override val targetDevice: Address? = null,
    override val requestId: String? = null,
    override val correlationId: CorrelationId? = null
) : DeviceMessage {

    override fun changeSource(block: (Name) -> Name): TelemetryPacket =
        copy(sourceDevice = sourceDevice.copy(device = block(sourceDevice.device)))
}