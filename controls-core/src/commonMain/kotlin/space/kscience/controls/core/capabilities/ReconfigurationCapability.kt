package space.kscience.controls.core.capabilities

import space.kscience.controls.api.features.ReconfigurableFeature
import space.kscience.controls.core.features.FeatureSpec
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.descriptors.MetaDescriptor

/**
 * A capability for devices that can be reconfigured at runtime without a full restart.
 */
public interface ReconfigurationCapability : DeviceCapability {

    /**
     * The descriptor defining the allowed configuration structure.
     */
    public val descriptor: MetaDescriptor

    /**
     * Applies new configuration parameters.
     * @param meta A [Meta] object containing the new configuration values.
     */
    public suspend fun reconfigure(meta: Meta)

    public companion object Key : CapabilityKey<ReconfigurationCapability> {
        override val id: String = "capability.reconfiguration"
    }

    override val key: CapabilityKey<*> get() = Key
}

/**
 * Typed specification binding [ReconfigurableFeature] (API) to [ReconfigurationCapability] (Core).
 */
public object ReconfigurationSpec : FeatureSpec<ReconfigurableFeature, ReconfigurationCapability>(
    id = "feature.reconfigurable",
    serializer = ReconfigurableFeature.serializer()
)