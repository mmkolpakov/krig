package space.kscience.controls.fsm

import space.kscience.controls.core.features.FeatureSpec
import space.kscience.controls.fsm.capability.LifecycleCapability
import space.kscience.controls.fsm.capability.OperationalFsmCapability
import space.kscience.controls.fsm.guards.OperationalGuardsFeature
import space.kscience.controls.core.capabilities.DeviceCapability
import space.kscience.controls.core.capabilities.CapabilityKey

public object LifecycleSpec : FeatureSpec<LifecycleFeature, LifecycleCapability>(
    id = "feature.lifecycle",
    serializer = LifecycleFeature.serializer()
)

public object OperationalFsmSpec : FeatureSpec<OperationalFsmFeature, OperationalFsmCapability>(
    id = "feature.operationalFsm",
    serializer = OperationalFsmFeature.serializer()
)

public interface GuardCapability : DeviceCapability {
    public companion object Key : CapabilityKey<GuardCapability> {
        override val id: String = "capability.operationalGuards"
    }
    override val key: CapabilityKey<*> get() = Key
}

public object OperationalGuardsSpec : FeatureSpec<OperationalGuardsFeature, GuardCapability>(
    id = "feature.operationalGuards",
    serializer = OperationalGuardsFeature.serializer()
)

public interface IntrospectionCapability : DeviceCapability {
    public companion object Key : CapabilityKey<IntrospectionCapability> {
        override val id: String = "capability.introspection"
    }
    override val key: CapabilityKey<*> get() = Key
}

public object IntrospectionSpec : FeatureSpec<IntrospectionFeature, IntrospectionCapability>(
    id = "feature.introspection",
    serializer = IntrospectionFeature.serializer()
)