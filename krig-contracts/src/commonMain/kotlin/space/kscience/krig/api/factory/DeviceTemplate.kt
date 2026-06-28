package space.kscience.krig.api.factory

import space.kscience.dataforge.context.Context
import space.kscience.dataforge.context.Factory
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.descriptors.MetaDescriptor
import space.kscience.dataforge.meta.descriptors.validate
import space.kscience.dataforge.names.Name
import space.kscience.krig.core.contracts.Device

/**
 * Device blueprint that pairs construction with a validatable [MetaDescriptor] for configuration.
 * The config type of [DeviceFactory] is erased; this API exposes the portable Meta schema.
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
