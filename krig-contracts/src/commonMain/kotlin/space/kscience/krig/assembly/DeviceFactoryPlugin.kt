@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package space.kscience.krig.assembly

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.update
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.toPersistentMap
import space.kscience.krig.api.discovery.ContributionTarget
import space.kscience.krig.api.discovery.TargetId
import space.kscience.krig.api.discovery.gather
import space.kscience.krig.api.discovery.pluginOrNull
import space.kscience.krig.api.discovery.requirePlugin
import space.kscience.krig.api.factory.DeviceFactory
import space.kscience.dataforge.context.AbstractPlugin
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.context.PluginFactory
import space.kscience.dataforge.context.PluginTag
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.Name

/**
 * DataForge plugin carrying runtime-mutable mappings from blueprint [Name] to
 * [DeviceFactory]. Jupyter cells and REPL scripts register factories through
 * this plugin; KSP-seeded contributors expose their own plugin at the same
 * [Target], and `Context.gather<DeviceFactory<*, *>>(DeviceFactoryPlugin.Target.id)`
 * merges both.
 */
public class DeviceFactoryPlugin(
    initial: Map<Name, DeviceFactory<*, *>> = emptyMap(),
    meta: Meta = Meta.EMPTY,
) : AbstractPlugin(meta) {

    override val tag: PluginTag get() = Companion.tag

    private val factories: AtomicReference<PersistentMap<Name, DeviceFactory<*, *>>> =
        AtomicReference(initial.toPersistentMap())

    public fun register(factory: DeviceFactory<*, *>) {
        factories.update { it.put(factory.id, factory) }
    }

    public fun remove(id: Name): Boolean {
        while (true) {
            val current = factories.load()
            if (!current.containsKey(id)) return false
            if (factories.compareAndSet(current, current.remove(id))) return true
        }
    }

    public operator fun get(id: Name): DeviceFactory<*, *>? = factories.load()[id]

    public fun ids(): Set<Name> = factories.load().keys

    override fun content(target: String): Map<Name, Any> = when (target) {
        Target.id -> factories.load().entries.associate { (id, f) -> id to f }
        else -> emptyMap()
    }

    @TargetId("krig.factory")
    public companion object : PluginFactory<DeviceFactoryPlugin> {
        /** Typed discovery target — `Context.gather(DeviceFactoryPlugin.Target)` returns `Map<Name, DeviceFactory<*, *>>`. */
        public val Target: ContributionTarget<DeviceFactory<*, *>> =
            ContributionTarget("krig.factory")

        override val tag: PluginTag = PluginTag("krig.factories", PluginTag.DATAFORGE_GROUP)

        override fun build(context: Context, meta: Meta): DeviceFactoryPlugin = DeviceFactoryPlugin(meta = meta)
    }
}

/**
 * Returns the [DeviceFactoryPlugin] attached to this [Context]. Throws if the plugin was
 * not installed at context construction.
 */
public fun Context.deviceFactories(): DeviceFactoryPlugin = requirePlugin(DeviceFactoryPlugin)

/**
 * Discovers a factory across every plugin contributing to [DeviceFactoryPlugin.Target].
 * Fast path: lookup in the local [DeviceFactoryPlugin]. Slow path: full gather across all
 * contributors at the target.
 */
public fun Context.findFactory(id: Name): DeviceFactory<*, *>? {
    pluginOrNull(DeviceFactoryPlugin)?.get(id)?.let { return it }
    return gather(DeviceFactoryPlugin.Target)[id]
}
