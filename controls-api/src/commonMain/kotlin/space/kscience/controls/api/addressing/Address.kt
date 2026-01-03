package space.kscience.controls.api.addressing

import kotlinx.serialization.Serializable
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.parseAsName
import space.kscience.dataforge.names.plus

/**
 * Represents a unique, network-wide address for a device within a hierarchical topology.
 *
 * Unlike simple flat addressing, this structure supports deep routing through multiple layers
 * of hubs (Federation/System of Systems).
 *
 * ### Format Specification
 * String representation: `route::device`
 * Example: `factory.line1.robot::arm.joint[1]`
 *
 * - **route**: The routing path to the hub managing the device. This is a hierarchical [Name].
 *              It may be empty for local addressing.
 * - **device**: The local name of the device within the final hub in the route.
 *
 * @property route The hierarchical path identifying the hub.
 * @property device The local, potentially hierarchical, name of the device within its hub.
 */
@Serializable
public data class Address(val route: Name, val device: Name) {
    override fun toString(): String = "$route::$device"

    /**
     * Convenience constructor creating an address from string representations.
     */
    public constructor(route: String, device: String) : this(route.parseAsName(), device.parseAsName())

    /**
     * Creates a new [Address] for a direct child of the device at this address.
     * This appends the child name to the [device] part of the address.
     *
     * @param childName The local name of the child device.
     * @return The full network-wide address of the child.
     */
    public fun resolveChild(childName: Name): Address =
        this.copy(device = this.device + childName)

    public companion object {
        /**
         * Parses a string representation of an address.
         * The expected format is "route::device".
         *
         * @throws IllegalArgumentException if the string format is invalid.
         */
        public fun parse(string: String): Address {
            val parts = string.split("::", limit = 2)
            require(parts.size == 2) { "Invalid address format. Expected 'route::device', but got '$string'." }
            return Address(parts[0].parseAsName(), parts[1].parseAsName())
        }

        /**
         * Parses a string representation of an address, returning null on failure.
         *
         * @return The parsed [Address] or `null` if the string format is invalid.
         */
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

/**
 * Extension function to parse a string into an [Address].
 *
 * @receiver The string to parse, expected format is "route::device".
 * @throws IllegalArgumentException if the string format is invalid.
 */
public fun String.toAddress(): Address = Address.parse(this)

/**
 * Extension function to safely parse a string into an [Address], returning null on failure.
 *
 * @receiver The string to parse.
 * @return The parsed [Address] or `null` if the string format is invalid.
 */
public fun String.toAddressOrNull(): Address? = Address.parseOrNull(this)