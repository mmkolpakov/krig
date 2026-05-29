package space.kscience.krig.core.contracts

import space.kscience.dataforge.io.Binary
import space.kscience.dataforge.io.asBinary
import space.kscience.dataforge.io.toByteArray
import space.kscience.krig.api.data.DataQuality
import space.kscience.krig.api.data.ObservedValue
import space.kscience.krig.api.descriptors.PropertyDescriptor
import space.kscience.krig.api.result.OperationOutcome
import space.kscience.krig.api.result.getOrThrow
import space.kscience.krig.api.result.map
import space.kscience.krig.core.meta.DeviceActionContract
import space.kscience.krig.core.meta.DevicePropertyContract
import space.kscience.dataforge.meta.*
import kotlin.time.Clock

/**
 * Extension functions for [DeviceBackend] providing typed binary data access.
 *
 * The throwing helpers are thin wrappers over the outcome-returning backend methods.
 * All extensions carry a `context(device: DeviceEnvironment)` because the underlying
 * [DeviceBackend] operations already require the current operation environment.
 */

/**
 * Reads a property as raw binary data.
 * @return The property value decoded as [ByteArray], or a failure when the raw value is not binary.
 */
context(device: DeviceEnvironment)
public suspend fun DeviceBackend.readBinaryOutcome(property: PropertyDescriptor): OperationOutcome<Binary> =
    this.readBinary(property)

context(device: DeviceEnvironment)
public suspend fun DeviceBackend.readBinaryBlock(property: PropertyDescriptor): Binary =
    this.readBinaryOutcome(property).getOrThrow()

context(device: DeviceEnvironment)
public suspend fun DeviceBackend.readBytesOutcome(property: PropertyDescriptor): OperationOutcome<ByteArray> =
    readBinaryOutcome(property).map { it.toByteArray() }

context(device: DeviceEnvironment)
public suspend fun DeviceBackend.readBytes(property: PropertyDescriptor): ByteArray =
    readBytesOutcome(property).getOrThrow()

/**
 * Writes raw binary data to a property.
 * @param data The byte payload to write.
 */
context(device: DeviceEnvironment)
public suspend fun DeviceBackend.writeBytes(property: PropertyDescriptor, data: ByteArray) {
    this.writeBinary(property, data.asBinary()).getOrThrow()
}

/** Wrap a [Meta] sample with timestamp and quality for observed-read paths. */
public fun observedMeta(
    value: Meta?,
    clock: Clock = Clock.System,
    quality: DataQuality = DataQuality.GOOD,
): ObservedValue<Meta?> = ObservedValue(value = value, time = clock.now(), quality = quality)

/** Successful observed Meta outcome for backend/demo bodies. */
public fun okObservedMeta(
    value: Meta?,
    clock: Clock = Clock.System,
    quality: DataQuality = DataQuality.GOOD,
): OperationOutcome<ObservedValue<Meta?>> = OperationOutcome.Ok(observedMeta(value, clock, quality))

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

/** Wrap a [Double] sample as observed root-value [Meta]. */
public fun observedMeta(
    value: Double,
    clock: Clock = Clock.System,
    quality: DataQuality = DataQuality.GOOD,
): ObservedValue<Meta?> = observedMeta(metaOf(value), clock, quality)

/** Successful observed [Double] outcome encoded as root-value Meta. */
public fun okObservedMeta(
    value: Double,
    clock: Clock = Clock.System,
    quality: DataQuality = DataQuality.GOOD,
): OperationOutcome<ObservedValue<Meta?>> = OperationOutcome.Ok(observedMeta(value, clock, quality))

/** Wrap an [Int] sample as observed root-value [Meta]. */
public fun observedMeta(
    value: Int,
    clock: Clock = Clock.System,
    quality: DataQuality = DataQuality.GOOD,
): ObservedValue<Meta?> = observedMeta(metaOf(value), clock, quality)

/** Successful observed [Int] outcome encoded as root-value Meta. */
public fun okObservedMeta(
    value: Int,
    clock: Clock = Clock.System,
    quality: DataQuality = DataQuality.GOOD,
): OperationOutcome<ObservedValue<Meta?>> = OperationOutcome.Ok(observedMeta(value, clock, quality))

/** Wrap a [Long] sample as observed root-value [Meta]. */
public fun observedMeta(
    value: Long,
    clock: Clock = Clock.System,
    quality: DataQuality = DataQuality.GOOD,
): ObservedValue<Meta?> = observedMeta(metaOf(value), clock, quality)

/** Successful observed [Long] outcome encoded as root-value Meta. */
public fun okObservedMeta(
    value: Long,
    clock: Clock = Clock.System,
    quality: DataQuality = DataQuality.GOOD,
): OperationOutcome<ObservedValue<Meta?>> = OperationOutcome.Ok(observedMeta(value, clock, quality))

/** Wrap a [Boolean] sample as observed root-value [Meta]. */
public fun observedMeta(
    value: Boolean,
    clock: Clock = Clock.System,
    quality: DataQuality = DataQuality.GOOD,
): ObservedValue<Meta?> = observedMeta(metaOf(value), clock, quality)

/** Successful observed [Boolean] outcome encoded as root-value Meta. */
public fun okObservedMeta(
    value: Boolean,
    clock: Clock = Clock.System,
    quality: DataQuality = DataQuality.GOOD,
): OperationOutcome<ObservedValue<Meta?>> = OperationOutcome.Ok(observedMeta(value, clock, quality))

/** Wrap a [String] sample as observed root-value [Meta]. */
public fun observedMeta(
    value: String,
    clock: Clock = Clock.System,
    quality: DataQuality = DataQuality.GOOD,
): ObservedValue<Meta?> = observedMeta(metaOf(value), clock, quality)

/** Successful observed [String] outcome encoded as root-value Meta. */
public fun okObservedMeta(
    value: String,
    clock: Clock = Clock.System,
    quality: DataQuality = DataQuality.GOOD,
): OperationOutcome<ObservedValue<Meta?>> = OperationOutcome.Ok(observedMeta(value, clock, quality))

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
    this.execute(spec, Unit)
