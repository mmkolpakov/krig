package space.kscience.controls.composite.dsl.streams

import kotlinx.serialization.serializer
import space.kscience.controls.composite.dsl.CompositeSpecDsl
import space.kscience.controls.composite.dsl.DeviceSpecification
import space.kscience.controls.core.contracts.Device
import space.kscience.controls.api.spec.QoS
import space.kscience.controls.core.contracts.StreamPort
import space.kscience.controls.core.meta.DeviceStreamSpec
import space.kscience.controls.api.descriptors.StreamDescriptor
import space.kscience.controls.api.spec.StreamDirection
import space.kscience.controls.api.identifiers.Permission
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.parseAsName
import kotlin.properties.PropertyDelegateProvider
import kotlin.properties.ReadOnlyProperty

/**
 * A DSL builder for creating [StreamDescriptor] instances.
 */
@CompositeSpecDsl
public class StreamDescriptorBuilder(public val name: Name) {
    /**
     * A human-readable description for the stream.
     */
    public var description: String? = null

    /**
     * A set of permissions required to access this stream.
     */
    public val permissions: MutableSet<Permission> = mutableSetOf()

    /**
     * An optional hint suggesting the expected data rate in Hertz.
     * UI elements can use this for scaling graphs or setting appropriate polling intervals.
     */
    public var suggestedRateHz: Double? = null

    /**
     * An optional hint indicating the primary direction of data flow.
     */
    public var direction: StreamDirection? = null

    /**
     * An optional hint suggesting the desired Quality of Service for the stream's transport.
     * The runtime may use this to select or configure the underlying transport mechanism.
     */
    public var deliveryHint: QoS? = null
}

/**
 * Creates a delegate for a device data stream and registers its specification.
 * The visibility of the stream is determined by the scope (`public`, `private`, etc.) in which this delegate is used.
 *
 * @param D The type of the device contract.
 * @param T The type of the primary data object transmitted over the stream. This is used for metadata purposes.
 * @param name An optional explicit name for the stream. If not provided, the delegated property name is used.
 * @param descriptorBuilder A DSL block to configure the stream's [StreamDescriptor].
 * @param get A suspendable factory lambda that creates a [StreamPort] instance. The runtime is responsible
 *            for managing the lifecycle of the created port.
 * @return A [PropertyDelegateProvider] that registers the stream spec and provides it as a read-only property.
 */
public inline fun <reified T, D : Device> DeviceSpecification<D>.stream(
    name: Name? = null,
    noinline descriptorBuilder: StreamDescriptorBuilder.() -> Unit = {},
    noinline get: suspend D.() -> StreamPort,
): PropertyDelegateProvider<DeviceSpecification<D>, ReadOnlyProperty<DeviceSpecification<D>, DeviceStreamSpec<D>>> =
    PropertyDelegateProvider { thisRef, property ->
        val streamName = name ?: property.name.parseAsName()

        val dslBuilder = StreamDescriptorBuilder(streamName).apply(descriptorBuilder)
        val fqName = serializer<T>().descriptor.serialName
        val descriptor = StreamDescriptor(
            name = streamName,
            dataTypeFqName = fqName,
            attributes = TODO()
        )

        val spec = object : DeviceStreamSpec<D> {
            override val name: Name = streamName
            override val descriptor: StreamDescriptor = descriptor
            override val get: suspend D.() -> StreamPort = get
        }

        thisRef.registerStreamSpec(spec)
        ReadOnlyProperty { _, _ -> spec }
    }