package space.kscience.krig.api.factory

import space.kscience.dataforge.context.Context
import space.kscience.dataforge.context.Factory
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.descriptors.MetaDescriptor
import space.kscience.dataforge.meta.descriptors.validate
import space.kscience.dataforge.names.Name
import space.kscience.krig.core.contracts.Device

/**
 * A device blueprint that pairs construction (DataForge [Factory]) with an introspectable, validatable
 * [MetaDescriptor] for its configuration `Meta`. A low-code/UI layer can discover the required config
 * shape and reject invalid input via [validate] before calling [build].
 *
 * The config type parameter `C` of [DeviceFactory] is intentionally erased here: a template is identified
 * by its device type [D] and its `Meta` schema, not by an in-memory config class.
 */
public interface DeviceTemplate<out D : Device> : Factory<D> {

    /** Stable identifier of the template (mirrors [DeviceFactory.id]). */
    public val id: Name

    /** Schema of the configuration [Meta] accepted by [build]. */
    public val descriptor: MetaDescriptor

    /** Returns `true` when [config] satisfies [descriptor]. */
    public fun validate(config: Meta): Boolean = descriptor.validate(config)
}

/** Adapts a [DeviceFactory] to a [DeviceTemplate] by attaching a configuration [descriptor]. */
public fun <D : Device> DeviceFactory<D, *>.asTemplate(descriptor: MetaDescriptor): DeviceTemplate<D> {
    val factory = this
    return object : DeviceTemplate<D> {
        override val id: Name = factory.id
        override val descriptor: MetaDescriptor = descriptor
        override fun build(context: Context, meta: Meta): D = factory.build(context, meta)
    }
}

/** Builds a [DeviceTemplate] from an [id], a [descriptor] and a [build] function, without subclassing. */
public fun <D : Device> DeviceTemplate(
    id: Name,
    descriptor: MetaDescriptor,
    build: (Context, Meta) -> D,
): DeviceTemplate<D> = object : DeviceTemplate<D> {
    override val id: Name = id
    override val descriptor: MetaDescriptor = descriptor
    override fun build(context: Context, meta: Meta): D = build(context, meta)
}
