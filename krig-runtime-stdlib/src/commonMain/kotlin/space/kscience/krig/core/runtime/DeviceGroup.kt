@file:OptIn(space.kscience.krig.core.PerformancePitfall::class)

package space.kscience.krig.core.runtime

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName
import space.kscience.krig.core.contracts.AbstractDevice
import space.kscience.krig.core.contracts.Device
import space.kscience.krig.core.contracts.DeviceNode
import space.kscience.krig.core.contracts.DeviceRuntime
import space.kscience.krig.core.contracts.GracefullyCloseable
import space.kscience.krig.core.contracts.asNodeMap
import space.kscience.krig.core.contracts.ignoreCleanupFailureSuspending
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
) : AbstractDevice(name, DeviceRuntime(context)), DeviceNode {

    public open val devices: Map<Name, Device> = children

    override val device: Device get() = this

    override val children: Map<Name, DeviceNode>
        get() = devices.asNodeMap()

    private fun tryResolveChild(fullName: Name): Pair<Device, Name>? {
        val tokens = fullName.tokens
        if (tokens.size < 2) return null // single token = own property, not child delegation
        val childName = tokens.first().asName()
        val child = devices[childName] ?: return null
        return child to Name(tokens.drop(1))
    }

    protected open suspend fun readOwnProperty(propertyName: Name): Meta {
        error("Property '$propertyName' not found on DeviceGroup '$name'. Children: ${devices.keys}")
    }

    protected open suspend fun writeOwnProperty(propertyName: Name, value: Meta) {
        error("Property '$propertyName' not writable on DeviceGroup '$name' for value $value.")
    }

    protected open suspend fun executeOwn(actionName: Name, argument: Meta?): Meta? {
        error("Action '$actionName' not found on DeviceGroup '$name' for argument $argument.")
    }

    override suspend fun readProperty(propertyName: Name): Meta {
        val resolved = tryResolveChild(propertyName)
        return if (resolved != null) {
            val (child, remaining) = resolved
            child.readProperty(remaining)
        } else {
            readOwnProperty(propertyName)
        }
    }

    override suspend fun writeProperty(propertyName: Name, value: Meta) {
        val resolved = tryResolveChild(propertyName)
        if (resolved != null) {
            val (child, remaining) = resolved
            child.writeProperty(remaining, value)
        } else {
            writeOwnProperty(propertyName, value)
        }
    }

    override suspend fun execute(actionName: Name, argument: Meta?): Meta? {
        val resolved = tryResolveChild(actionName)
        return if (resolved != null) {
            val (child, remaining) = resolved
            child.execute(remaining, argument)
        } else {
            executeOwn(actionName, argument)
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
            val jobs = devices.values.map { child ->
                async {
                    ignoreCleanupFailureSuspending {
                        if (child is GracefullyCloseable) {
                            child.closeGracefully(drainTimeout)
                        } else {
                            child.shutdown()
                        }
                    }
                }
            }
            jobs.awaitAll()
        }
    }

    private suspend fun shutdownChildDevices() {
        supervisorScope {
            val jobs = devices.values.map { child ->
                async {
                    ignoreCleanupFailureSuspending { child.shutdown() }
                }
            }
            jobs.awaitAll()
        }
    }
}
