package space.kscience.controls.connectivity

import space.kscience.controls.core.capabilities.CapabilityKey
import space.kscience.controls.core.capabilities.DeviceCapability
import space.kscience.controls.core.contracts.DeviceHub
import space.kscience.controls.core.features.FeatureSpec

/**
 * A capability that allows a device to act as a [DeviceHub], managing child devices.
 * This capability implements the [DeviceHub] interface, bridging the gap between
 * a specific device instance and the generic container contract.
 */
public interface CompositionCapability : DeviceCapability, DeviceHub {
    public companion object Key : CapabilityKey<CompositionCapability> {
        override val id: String = "capability.composition"
    }
    override val key: CapabilityKey<*> get() = Key
}

/**
 * Specification for the Composition feature.
 */
public object CompositionSpec : FeatureSpec<CompositionFeature, CompositionCapability>(
    id = "feature.composition",
    serializer = CompositionFeature.serializer()
)