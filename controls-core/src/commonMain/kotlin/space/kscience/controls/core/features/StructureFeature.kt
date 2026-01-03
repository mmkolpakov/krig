package space.kscience.controls.core.features

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import space.kscience.controls.core.contracts.Device
import space.kscience.controls.core.descriptors.ActionDescriptor
import space.kscience.controls.core.descriptors.PropertyDescriptor
import space.kscience.controls.core.descriptors.StreamDescriptor
import space.kscience.controls.core.runtime.HydratableDeviceState
import space.kscience.controls.core.serialization.serializableToMeta
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.Name

//TODO("not used now")
/**
 * Defines the static structure (API surface) of the device.
 * This feature contains the descriptors that are exposed to external clients and tools.
 */
@Serializable
@SerialName(StructureFeature.ID)
public data class StructureFeature(
    val properties: Map<Name, PropertyDescriptor> = emptyMap(),
    val actions: Map<Name, ActionDescriptor> = emptyMap(),
    val streams: Map<Name, StreamDescriptor> = emptyMap()
) : Feature {
    override val key: FeatureKey<*> get() = StructureFeature
    override val capability: String = "space.kscience.controls.core.structure"

    override fun toMeta(): Meta = serializableToMeta(serializer(), this)

    public companion object : FeatureKey<StructureFeature> {
        public const val ID: String = "feature.structure"
        override val id: String = ID
    }
}

/**
 * Contains the executable logic for device properties (readers and writers).
 * This feature is [Transient] and exists only in the runtime memory.
 */
public class PropertyLogicFeature<D : Device>(
    public val readers: Map<Name, suspend D.() -> Any?> = emptyMap(),
    public val writers: Map<Name, suspend D.(Any?) -> Unit> = emptyMap(),
    public val derivedStateFactories: Map<Name, HydratableDeviceState<D, *>> = emptyMap()
) : Feature {
    override val key: FeatureKey<*> get() = PropertyLogicFeature
    override val capability: String = "space.kscience.controls.core.logic.properties"

    // Not serializable
    override fun toMeta(): Meta = Meta.EMPTY

    public companion object : FeatureKey<PropertyLogicFeature<*>> {
        override val id: String = "feature.logic.properties"
    }
}

/**
 * Contains the executable logic for device actions.
 * This feature is [Transient] and exists only in the runtime memory.
 */
public class ActionLogicFeature<D : Device>(
    public val executors: Map<Name, suspend D.(Meta?) -> Meta?> = emptyMap()
) : Feature {
    override val key: FeatureKey<*> get() = ActionLogicFeature
    override val capability: String = "space.kscience.controls.core.logic.actions"

    // Not serializable
    override fun toMeta(): Meta = Meta.EMPTY

    public companion object : FeatureKey<ActionLogicFeature<*>> {
        override val id: String = "feature.logic.actions"
    }
}