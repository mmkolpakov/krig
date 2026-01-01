package space.kscience.controls.core.contracts

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import space.kscience.controls.core.composition.ChildComponentConfig
import space.kscience.controls.core.connectivity.PeerBlueprint
import space.kscience.controls.core.connectivity.PeerConnection
import space.kscience.controls.core.descriptors.ActionDescriptor
import space.kscience.controls.core.descriptors.PropertyDescriptor
import space.kscience.controls.core.descriptors.StreamDescriptor
import space.kscience.controls.core.features.Feature
import space.kscience.controls.core.identifiers.BlueprintId
import space.kscience.controls.core.meta.MemberTag
import space.kscience.controls.core.serialization.serializableToMeta
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.MetaRepr
import space.kscience.dataforge.names.Name

@Serializable
@SerialName("deviceBlueprint")
public data class BlueprintMeta(
    val id: BlueprintId,
    val version: String = "0.1.0",
    val deviceContractFqName: String,
    val features: Map<String, Feature>,
    val peerConnections: Map<Name, PeerBlueprint<out PeerConnection>>,
    val children: Map<Name, ChildComponentConfig>,
    val properties: Collection<PropertyDescriptor>,
    val actions: Collection<ActionDescriptor>,
    val streams: Collection<StreamDescriptor>,
    val meta: Meta,
    val stateMigratorId: String? = null,
    val tags: Set<MemberTag> = emptySet(),
) : MetaRepr {
    override fun toMeta(): Meta = serializableToMeta(serializer(), this)
}