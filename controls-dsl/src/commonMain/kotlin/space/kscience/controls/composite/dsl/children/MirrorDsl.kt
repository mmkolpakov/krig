package space.kscience.controls.composite.dsl.children

import kotlinx.serialization.serializer
import space.kscience.controls.composite.dsl.CompositeSpecBuilder
import space.kscience.controls.composite.dsl.properties.PropertyDescriptorBuilder
import space.kscience.controls.composite.dsl.property
import space.kscience.controls.core.contracts.Device
import space.kscience.controls.connectivity.MirrorEntry
import space.kscience.controls.connectivity.RemoteMirrorFeature
import space.kscience.controls.core.meta.DevicePropertySpec
import space.kscience.controls.api.descriptors.PropertyKind
import space.kscience.dataforge.meta.MetaConverter
import space.kscience.dataforge.names.Name

/**
 * A DSL builder for configuring remote property mirrors.
 * This class is not intended for direct instantiation by users.
 */
public class MirrorBuilder<D : Device>(
    @PublishedApi
    internal val specBuilder: CompositeSpecBuilder<D>
) {
    @PublishedApi
    internal val entries: MutableList<MirrorEntry> = mutableListOf()

    public inline fun <reified T> registerMirror(
        remoteChildName: Name,
        remoteProperty: DevicePropertySpec<*, T>,
        localName: Name?,
        converter: MetaConverter<T>,
        valueTypeName: String,
        crossinline descriptorBuilder: PropertyDescriptorBuilder.() -> Unit
    ): DevicePropertySpec<D, T> {
        val finalLocalName = localName ?: Name.of(remoteChildName.toString(), remoteProperty.name.toString())

        val entry = MirrorEntry(
            remoteChildName = remoteChildName,
            remotePropertyName = remoteProperty.name,
            localPropertyName = finalLocalName,
            valueTypeName = valueTypeName
        )
        entries.add(entry)

        return specBuilder.property(
            name = finalLocalName,
            converter = converter,
            descriptorBuilder = {
                this.kind = PropertyKind.DERIVED
                description = "A mirror of property '${remoteProperty.name}' from remote child '$remoteChildName'."
                descriptorBuilder()
            }
        ) {
            error(
                "Property '$finalLocalName' is a remote mirror. " +
                        "Its value should be read from the device's reactive state, not through its read logic."
            )
        }
    }
}

/**
 * A type-safe DSL function to declare a mirrored property. This function is `inline` to allow `reified` types.
 *
 * @param T The type of the property value.
 * @param remoteChildName The local name of the remote child (defined via `remoteChild`).
 * @param remoteProperty The [DevicePropertySpec] of the property on the remote device.
 * @param localName The name for the local proxy property. Defaults to `remoteChildName.remotePropertyName`.
 * @param descriptorBuilder A block to configure the local property's descriptor.
 * @return The [DevicePropertySpec] for the newly created local proxy property, allowing it to be used in other DSL constructs.
 */
public inline fun <reified T> MirrorBuilder<*>.mirror(
    remoteChildName: Name,
    remoteProperty: DevicePropertySpec<*, T>,
    localName: Name? = null,
    converter: MetaConverter<T>,
    noinline descriptorBuilder: PropertyDescriptorBuilder.() -> Unit = {}
): DevicePropertySpec<*, T> {
    return registerMirror(
        remoteChildName,
        remoteProperty,
        localName,
        converter,
        serializer<T>().descriptor.serialName,
        descriptorBuilder
    )
}

/**
 * A DSL scope for declaring remote property mirrors.
 * This function automatically collects all declared mirrors and registers the [RemoteMirrorFeature]
 * on the device blueprint.
 */
public fun <D : Device> CompositeSpecBuilder<D>.mirrors(block: MirrorBuilder<D>.() -> Unit) {
    val builder = MirrorBuilder(this).apply(block)
    if (builder.entries.isNotEmpty()) {
        feature(RemoteMirrorFeature(builder.entries))
    }
}