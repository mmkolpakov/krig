package space.kscience.controls.api.structure

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import space.kscience.controls.common.meta.serializableToMeta
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.Name

/**
 * The "Portable" description of a device (The Manifest).
 *
 * This class is a pure Data Transfer Object (DTO) containing no executable code.
 * It is fully serializable and safe to transmit over a network, store in a database, or version control.
 *
 * It acts as the "DNA" of the device, containing all necessary instructions for the Device Hub
 * to reconstruct ("rehydrate") the live device entity using registered factories.
 *
 * @property id The unique identifier of the device type (Blueprint ID). Example: "vendor.sensor.v1".
 * @property driverId The identifier of the driver factory required to operate this device.
 *                    The Hub uses this ID to look up the executable driver logic.
 * @property driverConfig The configuration parameters passed to the driver during the [space.kscience.controls.api.io.DeviceIO.configure] phase.
 *                        This typically contains connection parameters (IP, Port, Bus ID).
 * @property properties A map of descriptors defining the device's state variables (properties).
 *                      Key is the hierarchical property name.
 * @property actions A map of descriptors defining the device's executable commands (actions).
 *                   Key is the hierarchical action name.
 * @property features A list of feature configurations (capabilities).
 *                    Each [Meta] item represents a serialized [space.kscience.controls.api.meta.FeatureSpec]
 *                    and MUST contain a "capabilityType" field.
 * @property meta Additional global metadata for the device (e.g., human-readable description, tags, UI hints).
 */
@Serializable
@SerialName("manifest")
public data class DeviceManifest(
    val id: String,
    val driverId: String,
    val driverConfig: Meta = Meta.EMPTY,
    val properties: Map<Name, PropertyDescriptor> = emptyMap(),
    val actions: Map<Name, ActionDescriptor> = emptyMap(),
    val features: List<Meta> = emptyList(),
    val meta: Meta = Meta.EMPTY
) : DeviceBlueprint {
    /**
     * Converts this manifest into a generic [Meta] representation using standard serialization.
     */
    override fun toMeta(): Meta = serializableToMeta(serializer(), this)
}