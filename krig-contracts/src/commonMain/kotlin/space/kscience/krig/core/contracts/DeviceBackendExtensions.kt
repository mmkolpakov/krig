package space.kscience.krig.core.contracts

import space.kscience.krig.api.descriptors.PropertyDescriptor
import space.kscience.krig.api.result.getOrThrow
import space.kscience.krig.core.meta.DeviceActionContract
import space.kscience.krig.core.meta.DevicePropertyContract
import space.kscience.dataforge.meta.*

/**
 * Extension functions for [DeviceBackend] providing typed binary data access.
 *
 * Default implementations delegate to [DeviceBackend.read]/[DeviceBackend.write]
 * using DataForge's built-in ByteArrayValue codec. All extensions carry a
 * `context(device: DeviceEnvironment)` because the underlying [DeviceBackend.read] / [write] /
 * [execute] do.
 */

/**
 * Reads a property as raw binary data.
 * @return The property value decoded as [ByteArray], or an empty array if the property
 *         has no binary value.
 */
context(device: DeviceEnvironment)
public suspend fun DeviceBackend.readBinary(property: PropertyDescriptor): ByteArray {
    val meta = this.read(property).getOrThrow()
    return MetaConverter.byteArray.readOrNull(meta) ?: ByteArray(0)
}

/**
 * Writes raw binary data to a property.
 * @param data The byte payload to write.
 */
context(device: DeviceEnvironment)
public suspend fun DeviceBackend.writeBinary(property: PropertyDescriptor, data: ByteArray) {
    this.write(property, MetaConverter.byteArray.convert(data)).getOrThrow()
}

// Scalar Meta wrappers. Root-value form (`Meta(x.asValue())`) matches
// `MetaConverter.{double,int,long,boolean,string}` — interchangeable with `metaOf`.

/** Wrap a [Double] into a root-value [Meta] (same shape as `MetaConverter.double.convert`). */
public fun metaOf(value: Double): Meta = MetaConverter.double.convert(value)

/** Wrap an [Int] into a root-value [Meta]. */
public fun metaOf(value: Int): Meta = MetaConverter.int.convert(value)

/** Wrap a [Long] into a root-value [Meta]. */
public fun metaOf(value: Long): Meta = MetaConverter.long.convert(value)

/** Wrap a [Boolean] into a root-value [Meta]. */
public fun metaOf(value: Boolean): Meta = MetaConverter.boolean.convert(value)

/** Wrap a [String] into a root-value [Meta]. */
public fun metaOf(value: String): Meta = MetaConverter.string.convert(value)

/** Extract a [Double] from a root-value [Meta]. */
public val Meta.doubleValue: Double? get() = double

/** Extract an [Int] from a root-value [Meta]. */
public val Meta.intValue: Int? get() = int

/** Extract a [Long] from a root-value [Meta]. */
public val Meta.longValue: Long? get() = long

/** Extract a [Boolean] from a root-value [Meta]. */
public val Meta.booleanValue: Boolean? get() = boolean

/** Extract a [String] from a root-value [Meta]. */
public val Meta.stringValue: String? get() = string

/**
 * Reads [spec]'s value from the connection, decoding the resulting [Meta] through the spec's
 * own [MetaConverter].
 */
context(device: DeviceEnvironment)
public suspend fun <T> DeviceBackend.read(spec: DevicePropertyContract<T>): T {
    val meta = this.read(spec.descriptor).getOrThrow()
    return spec.converter.read(meta)
}

/**
 * Writes [value] to the connection's [spec]-typed property, encoding it through the spec's
 * own [MetaConverter].
 */
context(device: DeviceEnvironment)
public suspend fun <T> DeviceBackend.write(spec: DevicePropertyContract<T>, value: T) {
    this.write(spec.descriptor, spec.converter.convert(value)).getOrThrow()
}

/**
 * Executes [spec] with the typed [input], encoding/decoding through the spec's converters.
 */
context(device: DeviceEnvironment)
public suspend fun <I, O> DeviceBackend.execute(spec: DeviceActionContract<I, O>, input: I): O {
    val resultMeta = this.execute(spec.descriptor, spec.inputConverter.convert(input)).getOrThrow()
    return spec.outputConverter.read(resultMeta ?: Meta.EMPTY)
}

/** Convenience overload for unit-input actions. */
context(device: DeviceEnvironment)
public suspend fun <O> DeviceBackend.execute(spec: DeviceActionContract<Unit, O>): O =
    execute(spec, Unit)
