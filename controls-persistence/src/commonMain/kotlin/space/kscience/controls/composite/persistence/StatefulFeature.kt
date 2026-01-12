package space.kscience.controls.composite.persistence

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import space.kscience.controls.api.features.Feature
import space.kscience.controls.api.meta.serializableToMeta
import space.kscience.dataforge.meta.Meta

/**
 * A feature describing the state persistence capabilities of a device.
 *
 * @property supportsHotRestore If true, the device can restore its state immediately upon attachment/creation,
 *                              without waiting for a full start command.
 * @property migratorId An optional ID of a `DeviceMigrator` to use for schema migrations when restoring state.
 */
@Serializable
@SerialName("feature.stateful")
public data class StatefulFeature(
    val supportsHotRestore: Boolean = false,
    val migratorId: String? = null
) : Feature {
    override fun toMeta(): Meta = serializableToMeta(serializer(), this)
}