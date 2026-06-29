package space.kscience.krig.api.factory

import space.kscience.krig.api.utils.unit
import space.kscience.krig.core.contracts.Device
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.context.Factory
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.MetaConverter
import space.kscience.dataforge.meta.Scheme
import space.kscience.dataforge.meta.SchemeSpec
import space.kscience.dataforge.meta.descriptors.Described
import space.kscience.dataforge.meta.descriptors.MetaDescriptor
import space.kscience.dataforge.meta.descriptors.validate
import space.kscience.dataforge.misc.DfType
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.parseAsName

/**
 * Constructs a device from typed [C] config, wired as a DataForge `Factory<D>`. A factory is a pure
 * *constructor*, not a catalog document: the exportable [DeviceManifest][space.kscience.krig.core.contracts.DeviceManifest]
 * (descriptors, features, version) is a separate concern owned by the manifest catalog, so a factory
 * neither *is-a* nor carries a manifest. [configDescriptor] describes the construction config only,
 * so low-code loaders and tools can render/validate factory parameters without treating them as a
 * device contract. Subclasses implement [create].
 *
 * ```kotlin
 * public object ThermoFactory : DeviceFactory<Device, ThermoConfig>(
 *     id = "thermo".parseAsName(),
 *     configConverter = MetaConverter.serializable(),
 * ) {
 *     override fun create(context: Context, config: ThermoConfig): Device =
 *         ThermoDevice(context, config)
 * }
 * ```
 */
@DfType(DeviceFactory.TYPE)
public abstract class DeviceFactory<D : Device, C>(
    public val id: Name,
    public val configConverter: MetaConverter<C>,
    public val configDescriptor: MetaDescriptor? = null,
) : Factory<D>, Described {

    final override val descriptor: MetaDescriptor? get() = configDescriptor

    /** Synchronous construction. I/O belongs in `Device.start` or the backend's `connect()`. */
    public abstract fun create(context: Context, config: C): D

    /** Validates raw construction [meta] against [configDescriptor] before typed config conversion. */
    public fun validateConfig(meta: Meta) {
        val descriptor = configDescriptor ?: return
        if (!descriptor.validate(meta)) throw DeviceFactoryConfigValidationException(id, descriptor, meta)
    }

    final override fun build(context: Context, meta: Meta): D {
        validateConfig(meta)
        return create(context, configConverter.read(meta))
    }

    public companion object {
        /** DataForge type tag for `@DfType` discovery. */
        public const val TYPE: String = "device.factory"
    }
}

/** Thrown when dynamic factory config does not satisfy the factory's construction [descriptor]. */
public class DeviceFactoryConfigValidationException(
    public val factoryId: Name,
    public val descriptor: MetaDescriptor,
    public val config: Meta,
) : IllegalArgumentException("Config for DeviceFactory '$factoryId' does not match its configDescriptor")

/** Builder for a typed [DeviceFactory] without subclassing. */
public fun <D : Device, C> DeviceFactory(
    id: Name,
    configConverter: MetaConverter<C>,
    configDescriptor: MetaDescriptor? = null,
    create: (Context, C) -> D,
): DeviceFactory<D, C> = object : DeviceFactory<D, C>(id, configConverter, configDescriptor) {
    override fun create(context: Context, config: C): D = create(context, config)
}

public fun <D : Device, C> DeviceFactory(
    id: String,
    configConverter: MetaConverter<C>,
    configDescriptor: MetaDescriptor? = null,
    create: (Context, C) -> D,
): DeviceFactory<D, C> = DeviceFactory(id.parseAsName(), configConverter, configDescriptor, create)

/** Builder overload for DataForge Scheme-backed configs. */
public fun <D : Device, C : Scheme> DeviceFactory(
    id: Name,
    configSpec: SchemeSpec<C>,
    create: (Context, C) -> D,
): DeviceFactory<D, C> = DeviceFactory(id, configSpec, configSpec.descriptor, create)

public fun <D : Device, C : Scheme> DeviceFactory(
    id: String,
    configSpec: SchemeSpec<C>,
    create: (Context, C) -> D,
): DeviceFactory<D, C> = DeviceFactory(id.parseAsName(), configSpec, create)

/** No-config overload for `DeviceFactory<D, Unit>`. */
public fun <D : Device> DeviceFactory(
    id: Name,
    configDescriptor: MetaDescriptor? = null,
    create: (Context) -> D,
): DeviceFactory<D, Unit> = object : DeviceFactory<D, Unit>(id, MetaConverter.unit, configDescriptor) {
    override fun create(context: Context, config: Unit): D = create(context)
}

public fun <D : Device> DeviceFactory(
    id: String,
    configDescriptor: MetaDescriptor? = null,
    create: (Context) -> D,
): DeviceFactory<D, Unit> = DeviceFactory(id.parseAsName(), configDescriptor, create)
