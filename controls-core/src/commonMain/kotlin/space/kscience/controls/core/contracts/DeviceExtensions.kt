package space.kscience.controls.core.contracts

import space.kscience.controls.core.meta.DeviceActionSpec
import space.kscience.controls.core.meta.DevicePropertySpec
import space.kscience.controls.core.meta.MutableDevicePropertySpec
import space.kscience.controls.core.InternalControlsApi

// Type-safe extensions for interacting with devices using specifications.

/**
 * Reads the value of a property specified by [spec]. This is the primary, type-safe way to read a property.
 *
 * @param spec The [DevicePropertySpec] defining the property to read.
 * @return The property value of type [T].
 * @throws space.kscience.controls.core.faults.DevicePropertyException if the read fails or the returned value is null.
 */
public suspend fun <D : Device, T> D.read(spec: DevicePropertySpec<D, T>): T {
    @OptIn(InternalControlsApi::class)
    val meta = readProperty(spec.name)
    return spec.converter.read(meta)
}

/**
 * Writes a value to a mutable property specified by [spec]. This is the primary, type-safe way to write a property.
 *
 * @param spec The [MutableDevicePropertySpec] defining the property to write.
 * @param value The value of type [T] to write.
 */
public suspend fun <D : Device, T> D.write(spec: MutableDevicePropertySpec<D, T>, value: T) {
    @OptIn(InternalControlsApi::class)
    writeProperty(spec.name, spec.converter.convert(value))
}

/**
 * Executes an action specified by [spec] with the given [input]. This is the primary, type-safe way to execute an action.
 *
 * @param spec The [DeviceActionSpec] defining the action to execute.
 * @param input The input argument for the action.
 * @return The result of the action, or `null` if the action does not return a value.
 */
public suspend fun <D : Device, I, O> D.execute(spec: DeviceActionSpec<D, I, O>, input: I): O? {
    @OptIn(InternalControlsApi::class)
    val resultMeta = execute(spec.name, spec.inputConverter.convert(input))
    return resultMeta?.let { spec.outputConverter.read(it) }
}

/**
 * Executes an action that takes no input ([Unit]).
 * @see execute
 */
public suspend fun <D : Device, O> D.execute(spec: DeviceActionSpec<D, Unit, O>): O? = execute(spec, Unit)