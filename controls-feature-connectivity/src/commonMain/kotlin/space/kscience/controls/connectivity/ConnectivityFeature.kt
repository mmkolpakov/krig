package space.kscience.controls.connectivity

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import space.kscience.controls.api.features.Feature
import space.kscience.controls.api.features.FeatureKey
import space.kscience.controls.common.meta.serializableToMeta
import space.kscience.controls.services.transport.PeerBlueprint
import space.kscience.controls.services.transport.PeerConnection
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.Name

@Serializable
@SerialName(ConnectivityFeature.ID)
public data class ConnectivityFeature(
    val peerConnections: Map<Name, PeerBlueprint<out PeerConnection>>
) : Feature {
    override val key: FeatureKey<*> get() = ConnectivityFeature
    override val capability: String = CAPABILITY

    override fun toMeta(): Meta = serializableToMeta(serializer(), this)

    public companion object : FeatureKey<ConnectivityFeature> {
        public const val ID: String = "feature.connectivity"
        public const val CAPABILITY: String = "space.kscience.controls.connectivity"

        override val id: String = ID
    }
}