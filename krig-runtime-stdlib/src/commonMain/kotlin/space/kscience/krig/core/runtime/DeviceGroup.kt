@file:OptIn(space.kscience.krig.core.KrigPerformancePitfall::class)

package space.kscience.krig.core.runtime

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName
import space.kscience.krig.api.faults.GenericOperationFault
import space.kscience.krig.api.faults.OperationFaultException
import space.kscience.krig.api.faults.OperationFaultTypes
import space.kscience.krig.api.result.OperationOutcome
import space.kscience.krig.api.result.runCatchingOperation
import space.kscience.krig.core.contracts.AbstractDevice
import space.kscience.krig.core.contracts.Device
import space.kscience.krig.core.contracts.DeviceNode
import space.kscience.krig.core.contracts.DEFAULT_DEVICE_SHUTDOWN_TIMEOUT
import space.kscience.krig.core.contracts.DeviceRuntime
import space.kscience.krig.core.contracts.GracefullyCloseable
import space.kscience.krig.core.contracts.asNodeMap
import space.kscience.krig.core.contracts.closeDeviceBounded
import space.kscience.krig.core.contracts.ignoreNonCancellationFailure
import kotlin.time.Duration

/**
 * Runtime device that also exposes named child devices as a topology node.
 *
 * Hierarchical names route to children: `motor.position` reads `position`
 * from child `motor`. A name with no sub-path (single token) is always
 * treated as an own property — even if a child has the same name.
 *
 * Override [readOwnProperty]/[writeOwnProperty]/[executeOwn] for own properties.
 */
@OptIn(space.kscience.krig.core.InternalKrigApi::class, space.kscience.krig.core.UnstableKrigForSubclassing::class)
public open class DeviceGroup(
    name: Name,
    context: Context,
    children: Map<Name, Device>,
) : AbstractDevice(name, DeviceRuntime.from(context)), DeviceNode {

    public open val devices: Map<Name, Device> = children

    override val device: Device get() = this

    // Children are fixed for a plain DeviceGroup, so the node map is built once instead of on every
    // access (asNodeMap allocates a map + wrappers). MutableDeviceHub overrides this with its cached
    // topology flow for the dynamic case.
    override val children: Map<Name, DeviceNode> by lazy { devices.asNodeMap() }

    private fun tryResolveChild(fullName: Name): Pair<Device, Name>? {
        val tokens = fullName.tokens
        if (tokens.size < 2) return null // single token = own property, not child delegation
        val childName = tokens.first().asName()
        val child = devices[childName] ?: return null
        return child to Name(tokens.drop(1))
    }

    // Defaults throw OperationFaultException (caught by runCatchingOperation into a Fail outcome):
    // an unknown name is a predictable client error and must not promote the whole group to Failed.

    protected open suspend fun readOwnProperty(propertyName: Name): Meta =
        throw OperationFaultException(
            GenericOperationFault(
                faultType = OperationFaultTypes.UnknownProperty,
                message = "Property '$propertyName' not found on DeviceGroup '$name'. Children: ${devices.keys}",
            ),
        )

    protected open suspend fun writeOwnProperty(propertyName: Name, value: Meta) {
        throw OperationFaultException(
            GenericOperationFault(
                faultType = OperationFaultTypes.UnknownProperty,
                message = "Property '$propertyName' not writable on DeviceGroup '$name'.",
            ),
        )
    }

    protected open suspend fun executeOwn(actionName: Name, argument: Meta?): Meta? =
        throw OperationFaultException(
            GenericOperationFault(
                faultType = OperationFaultTypes.UnknownAction,
                message = "Action '$actionName' not found on DeviceGroup '$name'.",
            ),
        )

    override suspend fun doReadPropertyOutcome(propertyName: Name): OperationOutcome<Meta> {
        val resolved = tryResolveChild(propertyName)
        return if (resolved != null) {
            val (child, remaining) = resolved
            child.readPropertyOutcome(remaining)
        } else {
            runCatchingOperation { readOwnProperty(propertyName) }
        }
    }

    override suspend fun doWritePropertyOutcome(
        propertyName: Name,
        value: Meta,
    ): OperationOutcome<Unit> {
        val resolved = tryResolveChild(propertyName)
        return if (resolved != null) {
            val (child, remaining) = resolved
            child.writePropertyOutcome(remaining, value)
        } else {
            runCatchingOperation { writeOwnProperty(propertyName, value) }
        }
    }

    override suspend fun doExecuteOutcome(actionName: Name, argument: Meta?): OperationOutcome<Meta?> {
        val resolved = tryResolveChild(actionName)
        return if (resolved != null) {
            val (child, remaining) = resolved
            child.executeOutcome(remaining, argument)
        } else {
            runCatchingOperation { executeOwn(actionName, argument) }
        }
    }

    override suspend fun closeGracefully(drainTimeout: Duration) {
        closeGracefullyUsing(drainTimeout) {
            closeChildDevicesGracefully(drainTimeout)
            shutdownSelf()
        }
    }

    override suspend fun shutdown() {
        shutdownChildDevices()
        shutdownSelf()
    }

    override fun close() {
        for (child in devices.values) {
            ignoreNonCancellationFailure { child.close() }
        }
        super.close()
    }

    private suspend fun closeChildDevicesGracefully(drainTimeout: Duration) {
        supervisorScope {
            devices.values.map { child ->
                async {
                    closeDeviceBounded(child, drainTimeout + DEFAULT_DEVICE_SHUTDOWN_TIMEOUT) {
                        if (child is GracefullyCloseable) child.closeGracefully(drainTimeout) else child.shutdown()
                    }
                }
            }.awaitAll()
        }
    }

    private suspend fun shutdownChildDevices() {
        supervisorScope {
            devices.values.map { child ->
                async { closeDeviceBounded(child) { child.shutdown() } }
            }.awaitAll()
        }
    }
}
