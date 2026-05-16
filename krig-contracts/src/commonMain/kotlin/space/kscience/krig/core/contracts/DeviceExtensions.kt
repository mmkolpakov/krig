package space.kscience.krig.core.contracts

import space.kscience.krig.core.meta.DeviceActionSpec
import space.kscience.krig.core.meta.DevicePropertySpec
import space.kscience.krig.core.meta.MutableDevicePropertySpec

// Type-safe extensions for interacting with devices using specifications.

/**
 * Reads [spec] through the typed data-plane surface. A `TypedPipelineDevice`
 * decorates this path with gates / locks / retry / timeout / observers without forcing a
 * `Meta` allocation on the hot path.
 */
public suspend fun <D : Device, T> D.read(spec: DevicePropertySpec<D, T>): T =
    reader(spec).read()

/**
 * Writes [value] through the typed data-plane surface. The `Meta` write API remains the
 * serialization / control-plane boundary, not the default typed path.
 */
public suspend fun <D : Device, T> D.write(spec: MutableDevicePropertySpec<D, T>, value: T): Unit =
    writer(spec).write(value)

/**
 * Executes an action specified by [spec] with the given [input].
 *
 * @return The result of the action, or `null` if the action does not return a value.
 */
public suspend fun <D : Device, I, O> D.execute(spec: DeviceActionSpec<D, I, O>, input: I): O? =
    action(spec).execute(input)

/**
 * Executes an action that takes no input ([Unit]).
 * @see execute
 */
public suspend fun <D : Device, O> D.execute(spec: DeviceActionSpec<D, Unit, O>): O? = execute(spec, Unit)
