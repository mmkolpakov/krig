package space.kscience.krig.core.contracts

import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import space.kscience.krig.api.result.OperationOutcome
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName

/**
 * Batch operations across direct child devices of a [DeviceNode].
 *
 * All operations are concurrent via [supervisorScope]: one device's failure
 * does not cancel the others. Results are [OperationOutcome] per device.
 */

/** Reads [propertyName] from every child concurrently. */
public suspend fun DeviceNode.batchReadProperty(
    propertyName: Name,
): Map<Name, OperationOutcome<Meta>> = supervisorScope {
    children.entries.mapNotNull { (name, node) ->
        val device = node.device ?: return@mapNotNull null
        name to async { device.readPropertyOutcome(propertyName) }
    }.toMap().mapValues { (_, deferred) -> deferred.await() }
}

public suspend fun DeviceNode.batchReadProperty(
    propertyName: String,
): Map<Name, OperationOutcome<Meta>> = batchReadProperty(propertyName.asName())

/** Executes [actionName] on every child concurrently. */
public suspend fun DeviceNode.batchExecute(
    actionName: Name,
    argument: Meta? = null,
): Map<Name, OperationOutcome<Meta?>> = supervisorScope {
    children.entries.mapNotNull { (name, node) ->
        val device = node.device ?: return@mapNotNull null
        name to async { device.executeOutcome(actionName, argument) }
    }.toMap().mapValues { (_, deferred) -> deferred.await() }
}

public suspend fun DeviceNode.batchExecute(
    actionName: String,
    argument: Meta? = null,
): Map<Name, OperationOutcome<Meta?>> = batchExecute(actionName.asName(), argument)

/** Writes [value] to [propertyName] on every child concurrently. */
public suspend fun DeviceNode.batchWriteProperty(
    propertyName: Name,
    value: Meta,
): Map<Name, OperationOutcome<Unit>> = supervisorScope {
    children.entries.mapNotNull { (name, node) ->
        val device = node.device ?: return@mapNotNull null
        name to async { device.writePropertyOutcome(propertyName, value) }
    }.toMap().mapValues { (_, deferred) -> deferred.await() }
}
