package space.kscience.controls.api.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import space.kscience.controls.api.descriptors.ActionDescriptor
import space.kscience.controls.api.descriptors.PropertyDescriptor
import space.kscience.controls.api.descriptors.StreamDescriptor
import space.kscience.controls.common.meta.serializableToMeta
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.MetaRepr
import space.kscience.dataforge.names.Name

/**
 * A portable, serializable description of a device structure and configuration.
 * This object contains NO executable code, only data.
 *
 * @property id The unique identifier of the device type (blueprint ID).
 * @property driverId The identifier of the driver factory to use (e.g. "controls.driver.modbus").
 * @property driverConfig The configuration passed to the driver factory (e.g. connection settings).
 * @property properties A map of property descriptors defining the device's state variables.
 * @property actions A map of action descriptors defining the device's commands.
 * @property streams A map of stream descriptors defining continuous data flows.
 * @property features A list of feature configurations. Each Meta MUST contain a "capabilityType" field
 *                   to identify the factory.
 * @property meta Additional static metadata for the device.
 */
@Serializable
@SerialName("manifest")
public data class DeviceManifest(
    public val id: String,
    public val driverId: String,
    public val driverConfig: Meta = Meta.EMPTY,
    public val properties: Map<Name, PropertyDescriptor> = emptyMap(),
    public val actions: Map<Name, ActionDescriptor> = emptyMap(),
    public val streams: Map<Name, StreamDescriptor> = emptyMap(),
    public val features: List<Meta> = emptyList(),
    public val meta: Meta = Meta.EMPTY,
) : MetaRepr {
    override fun toMeta(): Meta = serializableToMeta(serializer(), this)
}