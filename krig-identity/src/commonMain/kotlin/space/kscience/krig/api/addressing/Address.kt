package space.kscience.krig.api.addressing

import kotlinx.serialization.Serializable
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.parseAsName
import space.kscience.dataforge.names.plus

/**
 * A network-wide address for a device within a hierarchical topology, supporting deep routing
 * through multiple hub layers.
 *
 * String representation: `route::device`, e.g. `factory.line1.robot::arm.joint[1]`.
 * The [route] identifies the hub managing the device; it may be empty for local addressing.
 */
@Serializable
public data class Address(val route: Name, val device: Name) {
    override fun toString(): String = "$route::$device"

    /** Convenience constructor creating an address from string representations. */
    public constructor(route: String, device: String) : this(route.parseAsName(), device.parseAsName())

    /**
     * Creates a new [Address] for a direct child by appending [childName] to the [device] part.
     */
    public fun resolveChild(childName: Name): Address =
        this.copy(device = this.device + childName)

    public companion object {
        /**
         * Parses a string of the form `route::device`.
         *
         * @throws IllegalArgumentException if the format is invalid.
         */
        public fun parse(string: String): Address {
            val parts = string.split("::", limit = 2)
            require(parts.size == 2) { "Invalid address format. Expected 'route::device', but got '$string'." }
            return Address(parts[0].parseAsName(), parts[1].parseAsName())
        }

        /** Parses a string of the form `route::device`, returning null if the format is invalid. */
        public fun parseOrNull(string: String): Address? {
            val parts = string.split("::", limit = 2)
            return if (parts.size == 2) {
                Address(parts[0].parseAsName(), parts[1].parseAsName())
            } else {
                null
            }
        }
    }
}

/** Parses this string as an [Address]; throws [IllegalArgumentException] on bad format. */
public fun String.toAddress(): Address = Address.parse(this)

/** Parses this string as an [Address], returning null on bad format. */
public fun String.toAddressOrNull(): Address? = Address.parseOrNull(this)
