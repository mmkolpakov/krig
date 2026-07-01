package space.kscience.krig.assembly

import kotlinx.serialization.Serializable
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.get
import space.kscience.dataforge.meta.string
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName
import space.kscience.dataforge.names.parseAsName
import space.kscience.krig.core.contracts.DeviceManifest
import space.kscience.krig.core.contracts.schemaHash
import space.kscience.krig.core.contracts.Device
import space.kscience.krig.core.runtime.DeviceGroup

/** Optional manifest guard for one declarative device instance. */
@Serializable
public data class DeviceManifestRequirement(
    public val manifestId: Name,
    public val version: String? = null,
    public val schemaHash: String? = null,
)

/** One device instance declared for [DeviceTopologySpec]. */
@Serializable
public data class DeviceInstanceSpec(
    public val factory: Name,
    public val config: Meta = Meta.EMPTY,
    public val manifest: DeviceManifestRequirement? = null,
)

/** Data-driven topology construction spec. This is construction input, not a device manifest. */
@Serializable
public data class DeviceTopologySpec(
    public val children: Map<Name, DeviceInstanceSpec> = emptyMap(),
)

/**
 * Assembles a [DeviceGroup] from a declarative [Meta], resolving each child through the context's
 * registered [DeviceFactory][space.kscience.krig.api.factory.DeviceFactory]s (see [Context.findFactory]).
 * The Kotlin-DSL `deviceGroup { … }` stays available; this is the data-driven (low-code) counterpart.
 *
 * Schema — children live under a `children` node, keyed by child device name:
 * ```
 * children {
 *     motor   { factory = "thermo"; config { setpoint = 20.0 } }
 *     sensor  { factory = "pt100" }
 * }
 * ```
 * Each child entry requires a `factory` id (resolved via [Context.findFactory]) and an optional `config`
 * node passed verbatim to the factory's `build`. A `manifest` node can reference a catalog manifest
 * by `id`/`manifestId` with optional `version` and `schemaHash` guards. Each child is built in its own
 * child [Context].
 */
public fun Context.metaDeviceGroup(name: Name, meta: Meta): DeviceGroup {
    return assembleDeviceTopology(name, meta.toDeviceTopologySpec())
}

/** Assembles a [DeviceGroup] from a typed [DeviceTopologySpec]. */
public fun Context.assembleDeviceTopology(name: Name, spec: DeviceTopologySpec): DeviceGroup {
    val children = LinkedHashMap<Name, Device>()
    spec.children.forEach { (childName, childSpec) ->
        validateManifestRequirement(childName, childSpec.manifest)
        val factoryId = childSpec.factory
        val factory = findFactory(factoryId)
            ?: error("No DeviceFactory registered for id '$factoryId' (child '$childName')")
        children[childName] = factory.build(buildContext(childName), childSpec.config)
    }
    return DeviceGroup(name, this, children)
}

/** String-name convenience over [assembleDeviceTopology]. */
public fun Context.assembleDeviceTopology(name: String, spec: DeviceTopologySpec): DeviceGroup =
    assembleDeviceTopology(name.parseAsName(), spec)

/** String-name convenience over [metaDeviceGroup]. */
public fun Context.metaDeviceGroup(name: String, meta: Meta): DeviceGroup =
    metaDeviceGroup(name.parseAsName(), meta)

/** Parses the stable Meta topology format used by [metaDeviceGroup]. */
public fun Meta.toDeviceTopologySpec(): DeviceTopologySpec {
    val children = this[CHILDREN_NODE]?.items.orEmpty().map { (token, childMeta) ->
        val childName = token.asName()
        val factory = (
            childMeta[FACTORY_KEY].string
                ?: error("metaDeviceGroup child '$childName' is missing the required '$FACTORY_KEY' id")
            ).parseAsName()
        childName to DeviceInstanceSpec(
            factory = factory,
            config = childMeta[CONFIG_NODE] ?: Meta.EMPTY,
            manifest = childMeta[MANIFEST_NODE]?.toManifestRequirement(childName),
        )
    }.toMap()
    return DeviceTopologySpec(children)
}

private fun Meta.toManifestRequirement(childName: Name): DeviceManifestRequirement {
    val manifestId = (
        this[MANIFEST_ID_KEY].string
            ?: this[LEGACY_MANIFEST_ID_KEY].string
            ?: error("metaDeviceGroup child '$childName' manifest is missing '$MANIFEST_ID_KEY'")
        ).parseAsName()
    return DeviceManifestRequirement(
        manifestId = manifestId,
        version = this[MANIFEST_VERSION_KEY].string,
        schemaHash = this[SCHEMA_HASH_KEY].string,
    )
}

private fun Context.validateManifestRequirement(childName: Name, requirement: DeviceManifestRequirement?) {
    requirement ?: return
    val manifest = findManifest(requirement.manifestId)
        ?: error("No DeviceManifest registered for id '${requirement.manifestId}' (child '$childName')")
    manifest.requireVersion(childName, requirement)
    manifest.requireSchemaHash(childName, requirement)
}

private fun DeviceManifest.requireVersion(childName: Name, requirement: DeviceManifestRequirement) {
    val expected = requirement.version ?: return
    require(version == expected) {
        "DeviceManifest '${requirement.manifestId}' version mismatch for child '$childName': " +
                "expected $expected, actual $version"
    }
}

private fun DeviceManifest.requireSchemaHash(childName: Name, requirement: DeviceManifestRequirement) {
    val expected = requirement.schemaHash ?: return
    val actual = schemaHash()
    require(actual == expected) {
        "DeviceManifest '${requirement.manifestId}' schema hash mismatch for child '$childName': " +
                "expected $expected, actual $actual"
    }
}

private const val CHILDREN_NODE = "children"
private const val FACTORY_KEY = "factory"
private const val CONFIG_NODE = "config"
private const val MANIFEST_NODE = "manifest"
private const val MANIFEST_ID_KEY = "id"
private const val LEGACY_MANIFEST_ID_KEY = "manifestId"
private const val MANIFEST_VERSION_KEY = "version"
private const val SCHEMA_HASH_KEY = "schemaHash"
