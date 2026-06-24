package space.kscience.krig.api.descriptors

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A semantic classification for a device property, describing its nature and origin.
 *
 * A deliberately closed vocabulary: the hierarchy is sealed so every consumer (catalogues, UI,
 * validation hooks) can rely on an exhaustive set of kinds on the wire. Domain-specific semantics
 * beyond this set (DERIVED, COMPUTED, STREAM, …) belong in descriptor attributes, not in new kinds.
 */
@Serializable
public sealed interface PropertyKind {
    /**
     * A property whose value is read directly from a physical device.
     */
    @Serializable
    @SerialName("kind.physical")
    public data object PHYSICAL : PropertyKind

    /**
     * A property that holds internal, managed state within a device.
     */
    @Serializable
    @SerialName("kind.logical")
    public data object LOGICAL : PropertyKind

    /**
     * The desired/target value of a control loop — written by clients and tracked by the device
     * (the "target" half of a target/actual pair, e.g. a temperature set-point).
     */
    @Serializable
    @SerialName("kind.setpoint")
    public data object SETPOINT : PropertyKind

    /**
     * The actual/observed value of a control loop — read from the process and compared against
     * its [SETPOINT] (the "actual" half of a target/actual pair, e.g. the measured temperature).
     */
    @Serializable
    @SerialName("kind.measured")
    public data object MEASURED : PropertyKind

    public companion object
}