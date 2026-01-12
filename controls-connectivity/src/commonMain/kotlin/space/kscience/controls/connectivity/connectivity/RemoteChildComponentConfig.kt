package space.kscience.controls.connectivity.connectivity

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import space.kscience.controls.api.addressing.Address
import space.kscience.controls.api.composition.ChildComponentConfig
import space.kscience.controls.api.features.Feature
import space.kscience.controls.api.identifiers.BlueprintId
import space.kscience.controls.api.meta.serializableToMeta
import space.kscience.dataforge.meta.Meta

/**
 * Configuration for a child device that exists remotely and is accessed via a local proxy.
 * This class contains all the necessary static information for the runtime to create and manage
 * the proxy and its connection to the remote device.
 *
 * @property blueprintId The [space.kscience.controls.core.contracts.DeviceBlueprint] id of the remote device.
 * @property blueprintVersion The version of the remote device's blueprint, used for compatibility checks.
 * @property features A set of configuration features for this child instance.
 * @property meta Additional metadata for configuring the local proxy instance.
 */
@Serializable
@SerialName("remote")
public data class RemoteChildComponentConfig(
    val target: Address,
    override val blueprintId: BlueprintId,
    override val blueprintVersion: String,
    override val features: Set<Feature> = emptySet(),
    override val meta: Meta = Meta.Companion.EMPTY,
) : ChildComponentConfig {
    override fun toMeta(): Meta = serializableToMeta(serializer(), this)
}