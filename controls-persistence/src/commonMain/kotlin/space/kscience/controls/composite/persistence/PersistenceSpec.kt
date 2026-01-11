package space.kscience.controls.composite.persistence

import space.kscience.controls.core.capabilities.CapabilityKey
import space.kscience.controls.core.capabilities.DeviceCapability
import space.kscience.controls.core.features.FeatureSpec
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.Name

/**
 * A contract for any component (Device or Capability) that needs to save its state.
 */
public interface Persistable {
    /**
     * Creates a serializable snapshot of the component's current state.
     */
    public suspend fun snapshot(): Meta

    /**
     * Restores the component's state from a snapshot.
     */
    public suspend fun restore(snapshot: Meta)
}

/**
 * A capability that manages the persistence of the device's state.
 * It is responsible for:
 * 1. Gathering state from all [Persistable] components (including the device itself).
 * 2. Saving/Loading state via the configured [SnapshotStore].
 * 3. Handling binary blobs if supported.
 */
public interface PersistenceCapability : DeviceCapability {

    /**
     * Forces a snapshot of the device state to be saved immediately.
     */
    public suspend fun save()

    /**
     * Forces a restoration of the device state from the last saved snapshot.
     */
    public suspend fun load()

    /**
     * Registers a component as [Persistable], ensuring its state is included
     * in the device-wide snapshot.
     *
     * @param key A unique key for this component within the snapshot.
     * @param persistable The component instance.
     */
    public fun registerPersistable(key: String, persistable: Persistable)

    /**
     * Snapshots binary blobs (large data) associated with the device state.
     * @return A map of named binary data blobs, or `null` if there are no blobs.
     */
    public suspend fun snapshotBlobs(): Map<Name, ByteArray>? = null

    /**
     * Restores binary blobs.
     */
    public suspend fun restoreBlobs(blobs: Map<Name, ByteArray>) {}

    public companion object Key : CapabilityKey<PersistenceCapability> {
        override val id: String = "capability.persistence"
    }

    override val key: CapabilityKey<*> get() = Key
}

/**
 * Typed specification binding [StatefulFeature] DTO to [PersistenceCapability].
 */
public object PersistenceSpec : FeatureSpec<StatefulFeature, PersistenceCapability>(
    id = "feature.stateful",
    serializer = StatefulFeature.serializer()
)