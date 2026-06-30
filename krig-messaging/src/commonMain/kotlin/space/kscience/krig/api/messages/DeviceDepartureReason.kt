package space.kscience.krig.api.messages

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Why a device left a topology or bus session. Used by both hub detach events and
 * device offline messages so supervision code does not translate between parallel reason trees.
 */
@Serializable
public sealed interface DeviceDepartureReason {
    @Serializable
    @SerialName("departure.graceful")
    public data object Graceful : DeviceDepartureReason

    @Serializable
    @SerialName("departure.parent-closed")
    public data object ParentClosed : DeviceDepartureReason

    @Serializable
    @SerialName("departure.failed")
    public data object Failed : DeviceDepartureReason

    @Serializable
    @SerialName("departure.timeout")
    public data object Timeout : DeviceDepartureReason

    @Serializable
    @SerialName("departure.transport-disconnected")
    public data object TransportDisconnected : DeviceDepartureReason

    @Serializable
    @SerialName("departure.crashed")
    public data object Crashed : DeviceDepartureReason

    @Serializable
    @SerialName("departure.evicted")
    public data object Evicted : DeviceDepartureReason

    @Serializable
    @SerialName("departure.released")
    public data object Released : DeviceDepartureReason

    @Serializable
    @SerialName("departure.decommissioned")
    public data object Decommissioned : DeviceDepartureReason

    @Serializable
    @SerialName("departure.transferred")
    public data object Transferred : DeviceDepartureReason

    /** Vendor-specific cause; [id] is stable, [message] is human-readable. */
    @Serializable
    @SerialName("departure.custom")
    public data class Custom(public val id: String, public val message: String? = null) : DeviceDepartureReason
}
