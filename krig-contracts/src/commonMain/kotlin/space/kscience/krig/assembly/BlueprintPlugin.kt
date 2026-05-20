@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package space.kscience.krig.assembly

import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.serialization.modules.SerializersModule
import space.kscience.krig.api.discovery.ContributionTarget
import space.kscience.krig.api.discovery.TargetId
import space.kscience.krig.api.discovery.gather
import space.kscience.krig.api.discovery.requirePlugin
import space.kscience.krig.api.serialization.krigApiSerializersModule
import space.kscience.krig.core.contracts.DeviceBlueprint
import space.kscience.dataforge.context.AbstractPlugin
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.context.PluginFactory
import space.kscience.dataforge.context.PluginTag
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.Name
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.update

/**
 * DataForge [AbstractPlugin] that carries a runtime-mutable map of [DeviceBlueprint]s.
 *
 * Discovery goes through `Context.gather<DeviceBlueprint<*>>(BlueprintPlugin.Target.id)`: results
 * from this plugin compose with any other contributor at the same target (e.g. KSP-generated
 * seeds) and inherit from parent contexts. No bespoke registry type — only `Context.gather`.
 *
 * CAS on a persistent map keeps concurrent `register` calls safe without suspending.
 */
public class BlueprintPlugin(
    initial: Map<Name, DeviceBlueprint<*>> = emptyMap(),
    meta: Meta = Meta.EMPTY,
) : AbstractPlugin(meta) {

    override val tag: PluginTag get() = Companion.tag

    /**
     * Contributes the krig core polymorphic serializers to [Context.serializationModule].
     * Overrides the DataForge [AbstractPlugin.serializerModule] (null by default). Consumers
     * who install only [BlueprintPlugin] automatically get a wire-ready `context.json`.
     */
    override val serializerModule: SerializersModule = krigApiSerializersModule

    private val blueprints: AtomicReference<PersistentMap<Name, DeviceBlueprint<*>>> =
        AtomicReference(initial.toPersistentMap())

    /** Registers [blueprint]; replaces any prior entry with the same `id`. */
    public fun register(blueprint: DeviceBlueprint<*>) {
        blueprints.update { it.put(blueprint.id, blueprint) }
    }

    /** Removes the blueprint with [id]; returns `true` if it was present. */
    public fun remove(id: Name): Boolean {
        while (true) {
            val current = blueprints.load()
            if (!current.containsKey(id)) return false
            if (blueprints.compareAndSet(current, current.remove(id))) return true
        }
    }

    /** Direct typed lookup. Prefer [Context.findBlueprint] when cross-plugin composition matters. */
    public operator fun get(id: Name): DeviceBlueprint<*>? = blueprints.load()[id]

    /** Snapshot of currently registered blueprint IDs. */
    public fun ids(): Set<Name> = blueprints.load().keys

    override fun content(target: String): Map<Name, Any> = when (target) {
        Target.id -> blueprints.load().entries.associate { (id, bp) -> id to bp }
        else -> emptyMap()
    }

    @TargetId("krig.blueprint")
    public companion object : PluginFactory<BlueprintPlugin> {
        /** Typed discovery target — `Context.gather(BlueprintPlugin.Target)` returns `Map<Name, DeviceBlueprint<*>>`. */
        public val Target: ContributionTarget<DeviceBlueprint<*>> =
            ContributionTarget("krig.blueprint")

        override val tag: PluginTag = PluginTag("krig.blueprints", PluginTag.DATAFORGE_GROUP)

        override fun build(context: Context, meta: Meta): BlueprintPlugin = BlueprintPlugin(meta = meta)
    }
}

/**
 * Returns the [BlueprintPlugin] attached to this [Context]. Throws if the plugin was not
 * installed via the Context builder: `Context("name") { plugin(BlueprintPlugin) }`.
 */
public fun Context.blueprints(): BlueprintPlugin = requirePlugin(BlueprintPlugin)

/**
 * Discovers a blueprint across every plugin contributing to [BlueprintPlugin.Target].
 * Match by [DeviceBlueprint.id] (stable regardless of contributor plugin).
 */
public fun Context.findBlueprint(id: Name): DeviceBlueprint<*>? =
    gather(BlueprintPlugin.Target).values.firstOrNull { it.id == id }

/** Installs all [items] into the [BlueprintPlugin] of this context. */
public fun Context.registerBlueprints(items: Iterable<DeviceBlueprint<*>>) {
    val plugin = blueprints()
    items.forEach { plugin.register(it) }
}
