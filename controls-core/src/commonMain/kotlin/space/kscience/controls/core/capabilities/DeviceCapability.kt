package space.kscience.controls.core.capabilities

import space.kscience.controls.core.contracts.Device

/**
 * A marker interface for a typed key used to retrieve a [DeviceCapability].
 *
 * @param C The specific type of the capability identified by this key.
 * @property id A unique string identifier for the capability, used for serialization and lookups.
 */
public interface CapabilityKey<C : DeviceCapability> {
    public val id: String
}

/**
 * Defines a composable unit of functionality that can be attached to a [Device].
 *
 * This interface is the cornerstone of the **Entity-Component System (ECS)** architecture for devices.
 * Instead of inheriting from monolithic interfaces,
 * a device implements basic contracts and *composes* these capabilities.
 *
 * Capabilities handle specific domains such as:
 * - Lifecycle management (`LifecycleCapability`)
 * - Automation (`PlanExecutorCapability`)
 * - Data streaming (`StreamCapability`)
 * - Persistence (`PersistenceCapability`)
 */
public interface DeviceCapability {

    /**
     * The unique key identifying the type of this capability.
     */
    public val key: CapabilityKey<*>

    /**
     * A lifecycle hook called when the capability is attached to a [Device] instance.
     * This is where the capability should initialize itself, subscribe to device properties,
     * register resources, or start background jobs.
     *
     * @param device The device instance to which this capability is being attached.
     */
    public suspend fun onAttach(device: Device) {}

    /**
     * A lifecycle hook called when the capability is detached from a [Device] instance,
     * or when the device itself is being destroyed.
     * Implementations must release all resources (jobs, sockets, file handles) here.
     *
     * @param device The device instance from which this capability is being detached.
     */
    public suspend fun onDetach(device: Device) {}
}