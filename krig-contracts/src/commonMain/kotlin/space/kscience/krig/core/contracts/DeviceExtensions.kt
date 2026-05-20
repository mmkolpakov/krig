package space.kscience.krig.core.contracts

import space.kscience.krig.core.meta.DeviceActionContract
import space.kscience.krig.core.meta.DevicePropertyContract
import space.kscience.krig.core.meta.MutableDevicePropertyContract

// Type-safe extensions for interacting with devices using specifications.

/**
 * Reads [spec] through the typed data-plane surface. A `PipelineDevice`
 * decorates this path with gates / locks / retry / timeout / observers without forcing a
 * `Meta` allocation on the hot path.
 */
public suspend fun <T> Device.read(spec: DevicePropertyContract<T>): T =
    reader(spec).read()

/**
 * Writes [value] through the typed data-plane surface. The `Meta` write API remains the
 * serialization / control-plane boundary, not the default typed path.
 */
public suspend fun <T> Device.write(spec: MutableDevicePropertyContract<T>, value: T): Unit =
    writer(spec).write(value)

/**
 * Executes an action specified by [spec] with the given [input].
 *
 * @return The result of the action, or `null` if the action does not return a value.
 */
public suspend fun <I, O> Device.execute(spec: DeviceActionContract<I, O>, input: I): O? =
    action(spec).execute(input)

/**
 * Executes an action that takes no input ([Unit]).
 * @see execute
 */
public suspend fun <O> Device.execute(spec: DeviceActionContract<Unit, O>): O? = execute(spec, Unit)
