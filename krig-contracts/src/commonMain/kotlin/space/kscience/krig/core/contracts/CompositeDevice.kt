@file:OptIn(space.kscience.krig.core.PerformancePitfall::class)

package space.kscience.krig.core.contracts

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName
import kotlin.time.Duration

/**
 * Composite [Device] implementing the Composite Pattern.
 *
 * Hierarchical names route to children: `motor.position` reads `position`
 * from child `motor`. A name with no sub-path (single token) is always
 * treated as an own property — even if a child has the same name.
 *
 * Override [readOwnProperty]/[writeOwnProperty]/[executeOwn] for own properties.
 */
@OptIn(space.kscience.krig.core.InternalKrigApi::class, space.kscience.krig.core.UnstableKrigForSubclassing::class)
public open class CompositeDevice(
    name: Name,
    context: Context,
    override val children: Map<Name, Device>,
) : AbstractDevice(name, DeviceRuntime(context)) {

    private fun tryResolveChild(fullName: Name): Pair<Device, Name>? {
        val tokens = fullName.tokens
        if (tokens.size < 2) return null // single token = own property, not child delegation
        val childName = tokens.first().asName()
        val child = children[childName] ?: return null
        return child to Name(tokens.drop(1))
    }

    protected open suspend fun readOwnProperty(propertyName: Name): Meta {
        error("Property '$propertyName' not found on CompositeDevice '$name'. Children: ${children.keys}")
    }

    protected open suspend fun writeOwnProperty(propertyName: Name, value: Meta) {
        error("Property '$propertyName' not writable on CompositeDevice '$name'.")
    }

    protected open suspend fun executeOwn(actionName: Name, argument: Meta?): Meta? {
        error("Action '$actionName' not found on CompositeDevice '$name'.")
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
            supervisorScope {
                val jobs = children.values.map { child ->
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
            shutdownSelf()
        }
    }
}
