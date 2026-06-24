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
        /**
         * Diagnostic label of the attached device implementation. Producers supply the most
         * stable identity they know (a manifest id when available, otherwise the runtime
         * class's simple name); the label is for humans and logs, not for dispatch.
         */
        public val deviceType: String,
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
        /** Diagnostic label of the replaced device implementation; see [Attached.deviceType]. */
        public val previousType: String,
        /** Diagnostic label of the replacement device implementation; see [Attached.deviceType]. */
        public val newType: String,
    ) : HubEvent
}
