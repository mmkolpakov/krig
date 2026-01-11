package space.kscience.controls.telemetry

import space.kscience.controls.core.capabilities.CapabilityKey
import space.kscience.controls.core.capabilities.DeviceCapability
import space.kscience.controls.core.features.FeatureSpec

/**
 * Capability interface for a device acting as a DataForge DataSource.
 */
public interface DataSourceCapability : DeviceCapability {
    public companion object Key : CapabilityKey<DataSourceCapability> {
        override val id: String = "capability.dataSource"
    }
    override val key: CapabilityKey<*> get() = Key
}

/**
 * Specification for the DataSource feature.
 */
public object DataSourceSpec : FeatureSpec<DataSourceFeature, DataSourceCapability>(
    id = "feature.dataSource",
    serializer = DataSourceFeature.serializer()
)

/**
 * Capability interface for a device that pushes telemetry updates.
 */
public interface TelemetrySource : DeviceCapability {
    public companion object Key : CapabilityKey<TelemetrySource> {
        override val id: String = "capability.telemetry"
    }
    override val key: CapabilityKey<*> get() = Key
}

/**
 * Specification for the Telemetry feature.
 */
public object TelemetrySpec : FeatureSpec<TelemetryFeature, TelemetrySource>(
    id = "feature.telemetry",
    serializer = TelemetryFeature.serializer()
)