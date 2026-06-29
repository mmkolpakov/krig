package space.kscience.krig.core.contracts

import space.kscience.dataforge.names.Name
import space.kscience.dataforge.provider.Path
import space.kscience.dataforge.provider.Provider
import space.kscience.dataforge.provider.length
import space.kscience.dataforge.provider.provide

/** DataForge provider targets exposed by [DeviceTopologyEntry]. */
public object DeviceTopologyTargets {
    public const val ENTRY: String = "krig.device.topology"
    public const val NODE: String = "krig.device.node"
    public const val DEVICE: String = "krig.device"
    public const val MANIFEST: String = "krig.device.manifest"
}

/**
 * DataForge [Provider] projection over a KRig [DeviceNode].
 *
 * The projection is read-only: lifecycle and ownership remain in [Device] / hub implementations.
 * Default chained lookup returns [DeviceTopologyEntry] so nested paths stay traversable; [device]
 * and [manifest] are projections of the same entry.
 */
public class DeviceTopologyEntry(
    public val path: Name,
    public val node: DeviceNode,
) : Provider {
    public val device: Device? get() = node.device

    public val manifest: DeviceManifest?
        get() {
            val currentDevice = device ?: return null
            return currentDevice.snapshotManifest(id = if (path == Name.EMPTY) currentDevice.name else path)
        }

    override val defaultTarget: String get() = DeviceTopologyTargets.ENTRY

    override val defaultChainTarget: String get() = DeviceTopologyTargets.ENTRY

    override fun content(target: String): Map<Name, Any> = when (target) {
        "", DeviceTopologyTargets.ENTRY -> childEntries()
        DeviceTopologyTargets.NODE -> node.children
        DeviceTopologyTargets.DEVICE -> node.children.mapNotNullValues { child -> child.device }
        DeviceTopologyTargets.MANIFEST -> childEntries().mapNotNullValues { entry -> entry.manifest }
        else -> emptyMap()
    }

    private fun childEntries(): Map<Name, DeviceTopologyEntry> =
        node.children.mapValues { (childName, childNode) ->
            DeviceTopologyEntry(path = path.childPath(childName), node = childNode)
        }
}

/** Builds a provider projection rooted at this topology node. */
public fun DeviceNode.asTopologyProvider(path: Name = Name.EMPTY): DeviceTopologyEntry =
    DeviceTopologyEntry(path, this)

/** Builds a provider projection rooted at this device. */
public fun Device.asTopologyProvider(path: Name = name): DeviceTopologyEntry =
    asNode().asTopologyProvider(path)

/** Resolves a nested topology entry. Empty [path] resolves to the receiver. */
public fun DeviceTopologyEntry.entry(path: Path): DeviceTopologyEntry? =
    if (path.length == 0) this else provide(path) as? DeviceTopologyEntry

/** Resolves a nested topology entry using DataForge path syntax (`area/motor`). */
public fun DeviceTopologyEntry.entry(path: String): DeviceTopologyEntry? =
    entry(Path.parse(path))

/** Resolves a nested device using DataForge path syntax (`area/motor`). */
public fun DeviceTopologyEntry.device(path: String): Device? =
    entry(path)?.device

/** Resolves a nested device manifest snapshot using DataForge path syntax (`area/motor`). */
public fun DeviceTopologyEntry.manifest(path: String): DeviceManifest? =
    entry(path)?.manifest

/** Snapshots a device's current public descriptors as a portable manifest document. */
public fun Device.snapshotManifest(
    id: Name = name,
    version: String = "runtime",
): DeviceManifest = manifestOf(
    id = id,
    properties = propertyDescriptors,
    actions = actionDescriptors,
    version = version,
)

private fun Name.childPath(child: Name): Name =
    if (this == Name.EMPTY) child else Name(tokens + child.tokens)

private inline fun <V, T : Any> Map<Name, V>.mapNotNullValues(value: (V) -> T?): Map<Name, T> =
    mapNotNull { (name, candidate) ->
        value(candidate)?.let { name to it }
    }.toMap()
