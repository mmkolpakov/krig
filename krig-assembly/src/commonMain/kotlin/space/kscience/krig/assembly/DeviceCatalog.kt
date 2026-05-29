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
import space.kscience.krig.core.contracts.DeviceManifest
import space.kscience.dataforge.context.AbstractPlugin
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.context.PluginFactory
import space.kscience.dataforge.context.PluginTag
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.Name
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.update

/**
 * DataForge [AbstractPlugin] that carries a runtime-mutable map of [DeviceManifest]s.
 *
 * Discovery goes through `Context.gather<DeviceManifest>(DeviceCatalog.Target.id)`: results
 * from this plugin compose with any other contributor at the same target (e.g. KSP-generated
 * seeds) and inherit from parent contexts. No bespoke registry type — only `Context.gather`.
 *
 * CAS on a persistent map keeps concurrent `register` calls safe without suspending.
 */
public class DeviceCatalog(
    initial: Map<Name, DeviceManifest> = emptyMap(),
    meta: Meta = Meta.EMPTY,
) : AbstractPlugin(meta) {

    override val tag: PluginTag get() = Companion.tag

    /**
     * Contributes the krig core polymorphic serializers to [Context.serializationModule].
     * Overrides the DataForge [AbstractPlugin.serializerModule] (null by default). Consumers
     * who install only [DeviceCatalog] automatically get a wire-ready `context.json`.
     */
    override val serializerModule: SerializersModule = krigApiSerializersModule

    private val manifests: AtomicReference<PersistentMap<Name, DeviceManifest>> =
        AtomicReference(initial.toPersistentMap())

    /** Registers [manifest]. Duplicate IDs are rejected; use [replace] for an explicit update. */
    public fun register(manifest: DeviceManifest) {
        while (true) {
            val current = manifests.load()
            require(manifest.id !in current) {
                "DeviceManifest '${manifest.id}' is already registered; call replace(...) to update it explicitly."
            }
            if (manifests.compareAndSet(current, current.put(manifest.id, manifest))) return
        }
    }

    /** Replaces the manifest with the same `id`, or registers it when absent. */
    public fun replace(manifest: DeviceManifest) {
        manifests.update { it.put(manifest.id, manifest) }
    }

    /** Removes the manifest with [id]; returns `true` if it was present. */
    public fun remove(id: Name): Boolean {
        while (true) {
            val current = manifests.load()
            if (!current.containsKey(id)) return false
            if (manifests.compareAndSet(current, current.remove(id))) return true
        }
    }

    /** Direct typed lookup. Prefer [Context.findManifest] when cross-plugin composition matters. */
    public operator fun get(id: Name): DeviceManifest? = manifests.load()[id]

    /** Snapshot of currently registered manifest IDs. */
    public fun ids(): Set<Name> = manifests.load().keys

    override fun content(target: String): Map<Name, Any> = when (target) {
        Target.id -> manifests.load().entries.associate { (id, manifest) -> id to manifest }
        else -> emptyMap()
    }

    @TargetId("krig.manifest")
    public companion object : PluginFactory<DeviceCatalog> {
        /** Typed discovery target — `Context.gather(DeviceCatalog.Target)` returns `Map<Name, DeviceManifest>`. */
        public val Target: ContributionTarget<DeviceManifest> =
            ContributionTarget("krig.manifest")

        override val tag: PluginTag = PluginTag("krig.manifests", PluginTag.DATAFORGE_GROUP)

        override fun build(context: Context, meta: Meta): DeviceCatalog = DeviceCatalog(meta = meta)
    }
}

/**
 * Returns the [DeviceCatalog] attached to this [Context]. Throws if the plugin was not
 * installed via the Context builder: `Context("name") { plugin(DeviceCatalog) }`.
 */
public fun Context.deviceCatalog(): DeviceCatalog = requirePlugin(DeviceCatalog)

/**
 * Discovers a Manifest across every plugin contributing to [DeviceCatalog.Target].
 * Match by [DeviceManifest.id] (stable regardless of contributor plugin).
 */
public fun Context.findManifest(id: Name): DeviceManifest? =
    gather(DeviceCatalog.Target).values.firstOrNull { it.id == id }

/** Installs all [items] into the [DeviceCatalog] of this context. */
public fun Context.registerManifests(items: Iterable<DeviceManifest>) {
    val plugin = deviceCatalog()
    items.forEach { plugin.register(it) }
}
