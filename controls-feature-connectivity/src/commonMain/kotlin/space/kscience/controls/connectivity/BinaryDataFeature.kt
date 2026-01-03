package space.kscience.controls.connectivity

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import space.kscience.controls.core.connectivity.PeerConnection
import space.kscience.controls.core.features.Feature
import space.kscience.controls.core.features.FeatureKey
import space.kscience.controls.core.serialization.serializableToMeta
import space.kscience.dataforge.meta.Meta

/**
 * A feature indicating that the device supports direct transfer of large binary data,
 * bypassing the standard message bus for efficiency. This is an analogue to `PeerConnection` from `controls-kt`.
 *
 * @property formats A list of supported binary content types or formats (e.g., "image/jpeg", "custom-binary-format").
 */
@Serializable
@SerialName(BinaryDataFeature.ID)
public data class BinaryDataFeature(
    val formats: List<String> = emptyList()
) : Feature {
    override val key: FeatureKey<*> get() = BinaryDataFeature
    override val capability: String = PeerConnection.CAPABILITY

    override fun toMeta(): Meta = serializableToMeta(serializer(), this)

    public companion object : FeatureKey<BinaryDataFeature> {
        public const val ID: String = "feature.binaryData"
        override val id: String = ID
    }
}