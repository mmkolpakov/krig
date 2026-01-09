package space.kscience.controls.core.factory

import space.kscience.controls.api.io.DeviceIO
import space.kscience.controls.api.meta.FeatureSpec
import space.kscience.controls.api.structure.DeviceManifest
import space.kscience.controls.core.InternalControlsApi
import space.kscience.controls.core.bundle.DeviceHub
import space.kscience.controls.core.bundle.DriverRegistry
import space.kscience.controls.core.bundle.FeatureRegistry
import space.kscience.controls.core.capability.Capability
import space.kscience.controls.core.device.DeviceEntity
import space.kscience.controls.core.faults.CompositeHubException
import space.kscience.controls.core.state.PropertyRegistry
import space.kscience.controls.core.state.QoSController
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.get
import space.kscience.dataforge.meta.string
import space.kscience.dataforge.names.Name

/**
 * The "Assembler" class responsible for the heavy-lifting of device creation.
 * It strictly separates the **Construction Phase** (parsing, allocation, binding)
 * from the **Execution Phase** (DeviceEntity).
 *
 * This class is stateless and thread-safe.
 */
@InternalControlsApi
public class DeviceFactory(
    private val driverRegistry: DriverRegistry,
    private val featureRegistry: FeatureRegistry
) {

    /**
     * Assembles a [DeviceEntity] from a [DeviceManifest].
     *
     * @param context The parent context (Hub context).
     * @param name The unique name for the new device.
     * @param manifest The declarative definition of the device.
     * @param hubReference A reference to the owner Hub (needed for Entity back-reference).
     */
    public fun assemble(
        context: Context,
        name: Name,
        manifest: DeviceManifest,
        hubReference: DeviceHub
    ): DeviceEntity {
        // 1. Data Plane Construction
        // We sort properties by name to ensure deterministic token assignment (0, 1, 2...).
        val sortedProperties = manifest.properties.entries.sortedBy { it.key.toString() }
        val registry = createPropertyRegistry(context, sortedProperties)

        // 2. Transport Plane Construction & Binding
        // This phase calculates the physical mask and configures the driver.
        val (driver, physicalMask) = createAndConfigureDriver(context, manifest, registry, name)

        // 3. Entity Instantiation (Suspended State)
        val device = DeviceEntity(
            hub = hubReference,
            name = name,
            manifest = manifest,
            driver = driver,
            properties = registry,
            physicalMask = physicalMask
        )

        // 4. Logic Plane Injection
        // We create capabilities after DeviceEntity because they might need access to it (via Sandbox)
        val capabilities = try {
            createCapabilities(device, manifest.features)
        } catch (e: Throwable) {
            try {
                driver.close()
            } catch (closeEx: Throwable) {
                e.addSuppressed(closeEx)
            }
            throw e
        }

        // 5. Final Wiring
        device.bindCapabilities(capabilities)

        return device
    }

    private fun createPropertyRegistry(
        context: Context,
        sortedProperties: List<Map.Entry<Name, space.kscience.controls.api.structure.PropertyDescriptor>>
    ): PropertyRegistry {
        val policyResolver = TelemetryPolicyResolver(context)
        val policies = policyResolver.resolve(sortedProperties)

        val qosController = QoSController(policies)
        val descriptorMap = sortedProperties.associate { it.toPair() }

        return PropertyRegistry(descriptorMap, qosController)
    }

    /**
     * Creates the driver and performs the critical "Binding" phase.
     * It maps the logical [PropertyToken]s from the registry to the physical addresses/registers
     * defined in the manifest properties.
     *
     * Returns the configured driver and a mask indicating which tokens are physical.
     */
    private fun createAndConfigureDriver(
        context: Context,
        manifest: DeviceManifest,
        registry: PropertyRegistry,
        deviceName: Name
    ): Pair<DeviceIO, BooleanArray> {
        // Instantiate
        val driverFactory = driverRegistry.get(manifest.driverId)
            ?: throw CompositeHubException("Driver factory '${manifest.driverId}' not found")

        val driver = try {
            driverFactory.create(context, manifest.driverConfig)
        } catch (e: Exception) {
            throw CompositeHubException("Failed to instantiate driver '${manifest.driverId}'", e)
        }

        // Configure (Binding)
        // We iterate over the *manifest* properties. The property attributes may contain
        // a "driver" block (e.g. {"driver": {"register": 4001, "type": "HOLDING"}}).
        // If "driver" block is present -> Physical. If absent -> Logical.

        val bindingMap = HashMap<Int, Meta>()
        val physicalMask = BooleanArray(registry.size) // Defaults to false (Logical)

        manifest.properties.forEach { (propName, desc) ->
            val token = registry.getToken(propName)
            if (token != null) {
                // Extract driver config from property attributes
                val driverConfig = desc.attributes["driver"]
                if (driverConfig != null) {
                    bindingMap[token.raw] = driverConfig
                    // Mark as physical in the mask (using token index)
                    // Token index is guaranteed to be within [0, size) by PropertyRegistry design
                    physicalMask[token.index] = true
                }
            }
        }

        try {
            driver.configure(bindingMap)
        } catch (e: Exception) {
            driver.close()
            throw CompositeHubException("Driver configuration failed for device '$deviceName'", e)
        }

        return driver to physicalMask
    }

    private fun createCapabilities(
        device: DeviceEntity,
        features: List<Meta>
    ): List<Capability> {
        val capabilities = ArrayList<Capability>()

        features.forEach { featureMeta ->
            val type = featureMeta[FeatureSpec.TYPE_KEY]?.string
                ?: error("Feature config missing '${FeatureSpec.TYPE_KEY}' discriminator")

            val featureFactory = featureRegistry.get(type)
                ?: throw CompositeHubException("Feature factory '$type' not found")

            try {
                val sandbox = device.createSandbox()
                val capability = featureFactory.create(sandbox, featureMeta)

                // Initialization phase
                capability.attach(sandbox)
                capabilities.add(capability)
            } catch (e: Exception) {
                throw CompositeHubException("Failed to install feature '$type'", e)
            }
        }

        return capabilities
    }
}