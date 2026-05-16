package space.kscience.krig.api.factory

import space.kscience.krig.api.descriptors.ActionDescriptor
import space.kscience.krig.api.descriptors.PropertyDescriptor
import space.kscience.krig.api.features.DeviceFeatureSpec
import space.kscience.krig.api.identifiers.BlueprintId
import space.kscience.krig.api.utils.unit
import space.kscience.krig.core.contracts.Device
import space.kscience.krig.core.contracts.DeviceBlueprint
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.context.Factory
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.MetaConverter
import space.kscience.dataforge.misc.DfType
import space.kscience.dataforge.names.Name

/**
 * A [DeviceBlueprint] that constructs its own device. Typed [C] config, DataForge
 * `Factory<D>` wiring. Subclasses implement [create] and override [DeviceBlueprint]
 * members as needed.
 *
 * ```kotlin
 * public object ThermoFactory : DeviceFactory<Device, ThermoConfig>(
 *     id = BlueprintId("thermo"),
 *     configConverter = MetaConverter.serializable(),
 * ) {
 *     override fun create(context: Context, config: ThermoConfig): Device =
 *         ThermoDevice(context, config)
 * }
 * ```
 */
@DfType(DeviceFactory.TYPE)
public abstract class DeviceFactory<D : Device, C>(
    override val id: BlueprintId,
    public val configConverter: MetaConverter<C>,
) : DeviceBlueprint<D>, Factory<D> {

    // --- Sensible DeviceBlueprint defaults — subclasses override as needed ---

    override val version: String get() = "0.1.0"
    override val features: Map<String, DeviceFeatureSpec> get() = emptyMap()
    override val properties: Map<Name, PropertyDescriptor> get() = emptyMap()
    override val actions: Map<Name, ActionDescriptor> get() = emptyMap()
    override val meta: Meta get() = Meta.EMPTY
    override val deviceContractFqName: String get() =
        "space.kscience.krig.core.contracts.Device"

    /** Synchronous construction. I/O belongs in `Device.start` or the backend's `connect()`. */
    public abstract fun create(context: Context, config: C): D

    final override fun build(context: Context, meta: Meta): D =
        create(context, configConverter.read(meta))

    override fun toMeta(): Meta = meta

    public companion object {
        /** DataForge type tag for `@DfType` discovery. */
        public const val TYPE: String = "device.factory"
    }
}

/** Inline builder for a typed [DeviceFactory] without subclassing. */
public fun <D : Device, C> DeviceFactory(
    id: BlueprintId,
    configConverter: MetaConverter<C>,
    create: (Context, C) -> D,
): DeviceFactory<D, C> = object : DeviceFactory<D, C>(id, configConverter) {
    override fun create(context: Context, config: C): D = create(context, config)
}

/** No-config overload for `DeviceFactory<D, Unit>`. */
public fun <D : Device> DeviceFactory(
    id: BlueprintId,
    create: (Context) -> D,
): DeviceFactory<D, Unit> = object : DeviceFactory<D, Unit>(id, MetaConverter.unit) {
    override fun create(context: Context, config: Unit): D = create(context)
}
