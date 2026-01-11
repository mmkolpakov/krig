package space.kscience.controls.telemetry

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import space.kscience.controls.api.features.Feature
import space.kscience.controls.common.meta.serializableToMeta
import space.kscience.dataforge.meta.Meta

/**
 * A feature indicating that the device can expose its properties as a `dataforge-data` `DataSource`.
 *
 * @property dataTypeString The common upper bound type for all data items produced by this source.
 */
@Serializable
@SerialName("feature.dataSource")
public data class DataSourceFeature(
    val dataTypeString: String?,
) : Feature {
    override fun toMeta(): Meta = serializableToMeta(serializer(), this)
}