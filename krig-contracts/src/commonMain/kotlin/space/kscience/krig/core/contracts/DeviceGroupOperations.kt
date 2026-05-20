package space.kscience.krig.core.contracts

import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import space.kscience.krig.api.result.OperationOutcome
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName

/**
 * Batch operations across all children of a [Device].
 *
 * All operations are concurrent via [supervisorScope]: one device's failure
 * does not cancel the others. Results are [OperationOutcome] per device.
 */

/** Reads [propertyName] from every child concurrently. */
public suspend fun Device.batchReadProperty(
    propertyName: Name,
): Map<Name, OperationOutcome<Meta>> = supervisorScope {
    children.entries.associate { (name, device) ->
        name to async { device.readPropertyOutcome(propertyName) }
    }.mapValues { (_, deferred) -> deferred.await() }
}

public suspend fun Device.batchReadProperty(
    propertyName: String,
): Map<Name, OperationOutcome<Meta>> = batchReadProperty(propertyName.asName())

/** Executes [actionName] on every child concurrently. */
public suspend fun Device.batchExecute(
    actionName: Name,
    argument: Meta? = null,
): Map<Name, OperationOutcome<Meta?>> = supervisorScope {
    children.entries.associate { (name, device) ->
        name to async { device.executeOutcome(actionName, argument) }
    }.mapValues { (_, deferred) -> deferred.await() }
}

public suspend fun Device.batchExecute(
    actionName: String,
    argument: Meta? = null,
): Map<Name, OperationOutcome<Meta?>> = batchExecute(actionName.asName(), argument)

/** Writes [value] to [propertyName] on every child concurrently. */
public suspend fun Device.batchWriteProperty(
    propertyName: Name,
    value: Meta,
): Map<Name, OperationOutcome<Unit>> = supervisorScope {
    children.entries.associate { (name, device) ->
        name to async { device.writePropertyOutcome(propertyName, value) }
    }.mapValues { (_, deferred) -> deferred.await() }
}
