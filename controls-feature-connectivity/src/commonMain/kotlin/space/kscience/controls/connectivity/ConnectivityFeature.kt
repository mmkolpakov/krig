package space.kscience.controls.connectivity

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import space.kscience.controls.api.features.Feature
import space.kscience.controls.common.meta.serializableToMeta
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.Name

/**
 * A feature that defines the peer-to-peer connections for a device.
 */
@Serializable
@SerialName("feature.connectivity")
public data class ConnectivityFeature(
    val peerConnections: Map<Name, PeerBlueprint<out PeerConnection>>
) : Feature {
    override fun toMeta(): Meta = serializableToMeta(serializer(), this)
}