package space.kscience.controls.core.composition

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import space.kscience.controls.api.composition.ChildComponentConfig
import space.kscience.controls.api.features.Feature
import space.kscience.controls.api.identifiers.BlueprintId
import space.kscience.controls.common.meta.serializableToMeta
import space.kscience.dataforge.meta.Meta

/**
 * Configuration for a child device that is instantiated locally within the same hub.
 *
 * @property blueprintId The [space.kscience.controls.core.contracts.DeviceBlueprint] id that defines the child's structure and logic.
 * @property features A set of configuration features for this child instance.
 * @property meta Additional metadata to be passed to the child device upon instantiation.
 */
@Serializable
@SerialName("local")
public data class LocalChildComponentConfig(
    override val blueprintId: BlueprintId,
    override val blueprintVersion: String,
    override val features: Set<Feature> = emptySet(),
    override val meta: Meta = Meta.Companion.EMPTY,
) : ChildComponentConfig {
    override fun toMeta(): Meta = serializableToMeta(serializer(), this)
}