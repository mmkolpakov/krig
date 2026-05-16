package space.kscience.krig.api.hub

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import space.kscience.krig.api.messages.DeviceDepartureReason
import kotlin.time.Instant
import space.kscience.dataforge.names.Name

/** Topology event emitted by a dynamic device hub. */
@Serializable
public sealed interface HubEvent {
    public val name: Name
    public val time: Instant

    @Serializable
    @SerialName("hub.attached")
    public data class Attached(
        override val name: Name,
        override val time: Instant,
        public val deviceContractFqName: String,
    ) : HubEvent

    @Serializable
    @SerialName("hub.detached")
    public data class Detached(
        override val name: Name,
        override val time: Instant,
        public val reason: DeviceDepartureReason,
    ) : HubEvent

    @Serializable
    @SerialName("hub.replaced")
    public data class Replaced(
        override val name: Name,
        override val time: Instant,
        public val previousContractFqName: String,
        public val newContractFqName: String,
    ) : HubEvent
}
