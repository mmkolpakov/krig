package space.kscience.krig.flow

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.Name
import space.kscience.krig.api.data.DataQuality
import space.kscience.krig.api.messages.DeviceMessage
import space.kscience.krig.api.serialization.SerializationContributor
import kotlin.time.Instant

/** Flow-specific message type ids contributed by the flow module. */
@Suppress("ConstPropertyName")
public object FlowMessageType {
    public const val Transfer: String = "flow.transfer"

    public val all: Set<String> = setOf(Transfer)
}

/**
 * Portable transfer observed at a flow boundary.
 *
 * Local `FlowGraph.step` remains deterministic and in-process. Use this DTO when a transfer must
 * cross a broker, journal, or delayed edge between distributed nodes.
 */
@Serializable
public data class DistributedFlowTransfer(
    public val connection: FlowConnection,
    public val amount: FlowAmount,
    public val unit: FlowUnit,
    public val sequence: Long? = null,
    public val effectiveAt: Instant? = null,
    public val quality: DataQuality = DataQuality.GOOD,
    public val attributes: Meta = Meta.EMPTY,
) {
    init {
        require(sequence == null || sequence >= 0) { "DistributedFlowTransfer.sequence must be non-negative" }
    }
}

/** Device message carrying a distributed flow transfer. */
@Serializable
@SerialName(FlowMessageType.Transfer)
public data class FlowTransferMessage(
    override val time: Instant,
    public val transfer: DistributedFlowTransfer,
    override val sourceDevice: Name,
    override val targetDevice: Name? = null,
) : DeviceMessage {
    override val messageType: String get() = FlowMessageType.Transfer
}

/** Polymorphic serializers contributed by `krig-flow` for applications that route flow messages. */
public val krigFlowSerializationContributor: SerializationContributor = SerializationContributor(
    SerializersModule {
        polymorphic(DeviceMessage::class) {
            subclass(FlowTransferMessage::class)
        }
    },
)
