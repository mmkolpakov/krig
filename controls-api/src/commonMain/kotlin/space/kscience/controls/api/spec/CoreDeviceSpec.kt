package space.kscience.controls.api.spec

import space.kscience.controls.api.descriptors.PropertyDescriptor
import space.kscience.controls.api.descriptors.PropertyKind
import space.kscience.controls.api.descriptors.attributes.AccessAttribute
import space.kscience.controls.api.descriptors.attributes.MetadataAttribute
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName

/**
 * A singleton object defining standard, system-level property descriptors for all devices.
 */
public object CoreDeviceSpec {

    /**
     * The standard property name for the device's lifecycle state.
     */
    public val LIFECYCLE_PROPERTY_NAME: Name = "sys.lifecycle".asName()

    /**
     * The standard property name for the device's health state.
     */
    public val HEALTH_PROPERTY_NAME: Name = "sys.health".asName()

    /**
     * The standard property name for the device's logical operational state.
     */
    public val OPERATIONAL_STATE_PROPERTY_NAME: Name = "sys.op_state".asName()

    /**
     * A descriptor for the Lifecycle State property.
     * Represents the formal lifecycle FSM state (e.g., "Starting", "Running", "Failed").
     * This property is Read-Only for external clients; state transitions must be
     * triggered via commands (actions) or capabilities.
     */
    public val LifecycleState: PropertyDescriptor = PropertyDescriptor(
        name = LIFECYCLE_PROPERTY_NAME,
        kind = PropertyKind.LOGICAL,
        valueTypeName = "space.kscience.controls.api.lifecycle.DeviceLifecycleState",
        attributes = setOf(
            MetadataAttribute(
                description = "The current formal lifecycle state of the device (e.g., Running, Stopped).",
                group = "System"
            ),
            AccessAttribute(readable = true, mutable = false)
        )
    )

    /**
     * A descriptor for the Health State property.
     * Indicates the physical or functional health of the device (e.g., "OK", "WARNING", "CRITICAL").
     * This is separate from the lifecycle state (a device can be "Running" but have a "WARNING" health).
     */
    public val HealthState: PropertyDescriptor = PropertyDescriptor(
        name = HEALTH_PROPERTY_NAME,
        kind = PropertyKind.LOGICAL,
        valueTypeName = "space.kscience.controls.api.data.HealthState",
        attributes = setOf(
            MetadataAttribute(
                description = "The aggregate health status of the device.",
                group = "System"
            ),
            AccessAttribute(readable = true, mutable = false)
        )
    )

    /**
     * A descriptor for the Operational State property.
     * Represents the logical business state of the device (e.g., "Idle", "Measuring", "Moving").
     * Unlike Lifecycle (which is generic), this is domain-specific.
     * It allows clients to understand what the device is actually *doing*.
     */
    public val OperationalState: PropertyDescriptor = PropertyDescriptor(
        name = OPERATIONAL_STATE_PROPERTY_NAME,
        kind = PropertyKind.LOGICAL,
        valueTypeName = "kotlin.String",
        attributes = setOf(
            MetadataAttribute(
                description = "The current operational activity or logic state of the device.",
                group = "System"
            ),
            AccessAttribute(readable = true, mutable = true)
        )
    )
}