package space.kscience.krig.assembly

import space.kscience.dataforge.context.Context
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.get
import space.kscience.dataforge.meta.string
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName
import space.kscience.dataforge.names.parseAsName
import space.kscience.krig.core.contracts.Device
import space.kscience.krig.core.runtime.DeviceGroup

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
 * node passed verbatim to the factory's `build`. Each child is built in its own child [Context].
 */
public fun Context.metaDeviceGroup(name: Name, meta: Meta): DeviceGroup {
    val children = LinkedHashMap<Name, Device>()
    meta[CHILDREN_NODE]?.items?.forEach { (token, spec) ->
        val childName = token.asName()
        val factoryId = (
            spec[FACTORY_KEY].string
                ?: error("metaDeviceGroup child '$childName' is missing the required '$FACTORY_KEY' id")
            ).parseAsName()
        val factory = findFactory(factoryId)
            ?: error("No DeviceFactory registered for id '$factoryId' (child '$childName')")
        children[childName] = factory.build(buildContext(childName), spec[CONFIG_NODE] ?: Meta.EMPTY)
    }
    return DeviceGroup(name, this, children)
}

/** String-name convenience over [metaDeviceGroup]. */
public fun Context.metaDeviceGroup(name: String, meta: Meta): DeviceGroup =
    metaDeviceGroup(name.parseAsName(), meta)

private const val CHILDREN_NODE = "children"
private const val FACTORY_KEY = "factory"
private const val CONFIG_NODE = "config"
