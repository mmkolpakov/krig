package space.kscience.controls.api.meta

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import space.kscience.controls.api.descriptors.ActionDescriptor
import space.kscience.controls.api.descriptors.PropertyDescriptor
import space.kscience.controls.api.descriptors.StreamDescriptor
import space.kscience.controls.api.features.Feature
import space.kscience.controls.api.identifiers.BlueprintId
import space.kscience.controls.common.meta.serializableToMeta
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.MetaRepr

@Serializable
@SerialName("deviceBlueprint")
public data class BlueprintMeta(
    val id: BlueprintId,
    val version: String = "0.1.0",
    val deviceContractFqName: String,
    val features: Map<String, Feature>,
    val properties: Collection<PropertyDescriptor>,
    val actions: Collection<ActionDescriptor>,
    val streams: Collection<StreamDescriptor>,
    val meta: Meta,
) : MetaRepr {
    override fun toMeta(): Meta = serializableToMeta(serializer(), this)
}