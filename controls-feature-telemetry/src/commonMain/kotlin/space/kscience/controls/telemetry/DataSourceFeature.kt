package space.kscience.controls.telemetry

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import space.kscience.controls.core.features.Feature
import space.kscience.controls.core.features.FeatureKey
import space.kscience.controls.core.serialization.serializableToMeta
import space.kscience.dataforge.meta.Meta

/**
 * A feature indicating that the device can expose its properties as a `dataforge-data` `DataSource`.
 * This enables seamless integration with the DataForge data processing and analysis ecosystem.
 *
 * @property dataTypeString The common upper bound type for all data items produced by this source, represented as a string.
 *                          The actual [KType] is not serializable and must be handled by the runtime.
 */
@Serializable
@SerialName(DataSourceFeature.ID)
public data class DataSourceFeature(
    val dataTypeString: String?,
) : Feature {
    override val key: FeatureKey<*> get() = DataSourceFeature
    override val capability: String get() = "space.kscience.dataforge.data.DataSource"

    override fun toMeta(): Meta = serializableToMeta(serializer(), this)

    public companion object : FeatureKey<DataSourceFeature> {
        public const val ID: String = "feature.dataSource"
        override val id: String = ID
    }
}