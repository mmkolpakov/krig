package space.kscience.controls.api.data

import kotlinx.serialization.Serializable

/**
 * Represents the aggregated health status of a device or a specific component.
 * This enum provides a unified "traffic light" mechanism for monitoring systems
 * to quickly assess the operational state of the infrastructure.
 */
@Serializable
public enum class DeviceHealth {
    /**
     * The device is operating normally. All systems are functional, and performance is within nominal parameters.
     * (Green Light)
     */
    OK,

    /**
     * The device is operational, but there are non-critical issues.
     * Examples: Performance degradation, high latency, non-critical sensor failure, maintenance required soon.
     * (Yellow Light)
     */
    WARNING,

    /**
     * The device is non-operational or in a critical failure state.
     * Immediate intervention is required.
     * Examples: Loss of communication with hardware, safety interlock tripped, critical exception in logic.
     * (Red Light)
     */
    ERROR,

    /**
     * The status of the device is currently unknown.
     * This usually happens during initialization, network partition, or before the first health check is completed.
     * (Grey Light)
     */
    UNKNOWN
}