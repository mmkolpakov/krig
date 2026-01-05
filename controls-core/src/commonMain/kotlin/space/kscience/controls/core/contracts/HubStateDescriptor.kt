package space.kscience.controls.core.contracts

import kotlinx.serialization.Serializable
import space.kscience.controls.api.identifiers.BlueprintId
import space.kscience.controls.api.lifecycle.DeviceLifecycleState
import space.kscience.controls.common.meta.serializableMetaConverter
import space.kscience.controls.common.meta.serializableToMeta
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.MetaConverter
import space.kscience.dataforge.meta.MetaRepr
import space.kscience.dataforge.names.Name

/**
 * A declarative, serializable old representing the desired state of an entire `DeviceHub`.
 * This descriptor is the cornerstone of the GitOps/declarative management pattern. A runtime component
 * (like a `SelfHealingHub`) can use this descriptor as the source of truth and continuously work to
 * bring the actual state of the hub into convergence with this desired state.
 *
 * This object can be serialized to YAML or JSON and stored in a version control system.
 *
 * @property devices A map where the key is the local [Name] of a device and the value is its
 *                   [DeviceStateDescriptor], defining what should be running in the hub.
 */
@Serializable
public data class HubStateDescriptor(
    val devices: Map<Name, DeviceStateDescriptor> = emptyMap(),
) : MetaRepr {
    override fun toMeta(): Meta = serializableToMeta(serializer(), this)

    public companion object {
        public val converter: MetaConverter<HubStateDescriptor> by lazy {
            serializableMetaConverter(serializer())
        }
    }
}

/**
 * A lazily-initialized [MetaConverter] for [HubStateDescriptor].
 *
 * Usage:
 * ```
 * val meta = MetaConverter.hubStateDescriptor.convert(myDescriptor)
 * val descriptor = MetaConverter.hubStateDescriptor.read(myMeta)
 * ```
 */
public val MetaConverter.Companion.hubStateDescriptor: MetaConverter<HubStateDescriptor> by lazy {
    HubStateDescriptor.converter
}