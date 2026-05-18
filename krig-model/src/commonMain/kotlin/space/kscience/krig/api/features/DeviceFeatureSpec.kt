package space.kscience.krig.api.features

import kotlinx.serialization.Polymorphic
import space.kscience.krig.api.annotations.PolymorphicBase
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.MetaRepr

/**
 * Serializable blueprint-level descriptor of a supported device capability
 * (`FsmFeature`, `CachingFeature`, …). The matching runtime object is
 * [DeviceCapability][space.kscience.krig.core.capabilities.DeviceCapability];
 * the installer is [DeviceFeatureInstaller][space.kscience.krig.core.contracts.DeviceFeatureInstaller].
 */
@Polymorphic
@PolymorphicBase
public interface DeviceFeatureSpec : MetaRepr {
    override fun toMeta(): Meta = Meta.EMPTY
}
