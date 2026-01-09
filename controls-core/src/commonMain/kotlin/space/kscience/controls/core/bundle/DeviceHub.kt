package space.kscience.controls.core.bundle

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import space.kscience.controls.api.structure.DeviceManifest
import space.kscience.controls.core.InternalControlsApi
import space.kscience.controls.core.device.DeviceEntity
import space.kscience.controls.core.factory.DeviceFactory
import space.kscience.controls.core.faults.CompositeHubException
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.context.ContextAware
import space.kscience.dataforge.names.Name

/**
 * The central container responsible for the lifecycle of devices within a process.
 *
 * It acts as:
 * 1. **Service Locator:** Holds registries for Drivers and Features.
 * 2. **Factory Facade:** Delegates to [DeviceFactory] for complex assembly.
 * 3. **Lifecycle Manager:** Ensures devices are started/stopped correctly.
 */
public class DeviceHub(
    override val context: Context
) : ContextAware {

    /**
     * Registry for hardware driver factories.
     */
    public val driverRegistry: DriverRegistry = DriverRegistry()

    /**
     * Registry for logical capability factories.
     */
    public val featureRegistry: FeatureRegistry = FeatureRegistry()

    @OptIn(InternalControlsApi::class)
    private val factory = DeviceFactory(driverRegistry, featureRegistry)

    private val _devices = HashMap<Name, DeviceEntity>()
    private val lock = SynchronizedObject()

    /**
     * Deploys a new device from a declarative manifest.
     *
     * @param name The unique name of the device within this Hub.
     * @param manifest The blueprint defining the device structure.
     * @return The deployed and started [DeviceEntity].
     * @throws CompositeHubException if assembly or startup fails.
     */
    @OptIn(InternalControlsApi::class)
    public suspend fun install(name: Name, manifest: DeviceManifest): DeviceEntity {
        // 1. Validation check before heavy assembly
        synchronized(lock) {
            if (_devices.containsKey(name)) {
                throw CompositeHubException("Device '$name' already exists in Hub")
            }
        }

        // 2. Delegate Assembly (Can be slow, involves IO)
        val device = try {
            factory.assemble(context, name, manifest, this)
        } catch (e: Exception) {
            throw CompositeHubException("Failed to assemble device '$name'", e)
        }

        // 3. Register safely
        synchronized(lock) {
            if (_devices.containsKey(name)) {
                throw CompositeHubException("Device '$name' already exists in Hub (race condition)")
            }
            _devices[name] = device
        }

        // 4. Lifecycle Start
        try {
            device.start()
        } catch (e: Exception) {
            synchronized(lock) {
                _devices.remove(name)
            }
            try { device.stop() } catch (_: Exception) {}

            throw CompositeHubException("Failed to start device '$name'", e)
        }

        return device
    }

    /**
     * Retrieves a running device by name.
     */
    public fun getDevice(name: Name): DeviceEntity? {
        return synchronized(lock) { _devices[name] }
    }

    /**
     * Uninstalls a device, stopping it and removing it from the registry.
     */
    public suspend fun uninstall(name: Name) {
        val device = synchronized(lock) { _devices.remove(name) }
        device?.stop()
    }
}