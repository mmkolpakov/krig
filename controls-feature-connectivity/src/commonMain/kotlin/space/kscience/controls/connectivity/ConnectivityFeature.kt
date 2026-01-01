package space.kscience.controls.connectivity

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import space.kscience.controls.core.connectivity.PeerBlueprint
import space.kscience.controls.core.connectivity.PeerConnection
import space.kscience.controls.core.features.Feature
import space.kscience.controls.core.serialization.serializableToMeta
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.Name

@Serializable
@SerialName("feature.connectivity")
public data class ConnectivityFeature(
    val peerConnections: Map<Name, PeerBlueprint<out PeerConnection>>
) : Feature {
    override val capability: String = CAPABILITY

    override fun toMeta(): Meta = serializableToMeta(serializer(), this)

    public companion object {
        public const val CAPABILITY: String = "space.kscience.controls.connectivity"
    }
}