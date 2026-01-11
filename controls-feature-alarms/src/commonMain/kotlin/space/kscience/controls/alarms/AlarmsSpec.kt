package space.kscience.controls.alarms

import space.kscience.controls.core.features.FeatureSpec
import space.kscience.controls.core.capabilities.DeviceCapability
import space.kscience.controls.core.capabilities.CapabilityKey

/**
 * A marker interface for the Alarms capability logic.
 */
public interface AlarmSource : DeviceCapability {
    public companion object Key : CapabilityKey<AlarmSource> {
        override val id: String = "capability.alarms"
    }

    override val key: CapabilityKey<*> get() = Key
}

/**
 * The typed specification binding the [AlarmsFeature] configuration to the [AlarmSource] capability.
 */
public object AlarmsSpec : FeatureSpec<AlarmsFeature, AlarmSource>(
    id = "feature.alarms",
    serializer = AlarmsFeature.serializer()
)