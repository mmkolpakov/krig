package space.kscience.controls.common.map

import space.kscience.controls.common.tokens.PropertyToken
import space.kscience.dataforge.names.Name

/**
 * A specialized, immutable, read-only map optimized for bidirectional lookup between [Name] and [PropertyToken].
 *
 * This structure is designed to be created once during the Device Binding Phase.
 *
 * **Performance Guarantees:**
 * 1. **Token -> Name (Fast Path):** O(1) access complexity using direct array indexing.
 *    Guarantees **Zero-Allocation** lookup, essential for emitting telemetry events in the driver's hot loop.
 * 2. **Name -> Token (Slow Path):** Standard HashMap lookup complexity. Used for RPC commands and configuration.
 *
 * @property nameToToken The underlying map for Name-to-Token resolution.
 * @property tokenToName The array for Token-to-Name resolution, indexed by [PropertyToken.index].
 */
public class TokenMap private constructor(
    private val nameToToken: Map<Name, PropertyToken>,
    private val tokenToName: Array<Name?>
) {

    /**
     * Fast Path: Retrieves the property [Name] corresponding to the given [token].
     *
     * This operation performs a bounds check and a direct array access.
     * It does not allocate iterators or map entry objects.
     *
     * @param token The property token.
     * @return The property name, or `null` if the token index is out of bounds or not mapped.
     */
    public operator fun get(token: PropertyToken): Name? {
        val index = token.index
        if (index < 0 || index >= tokenToName.size) return null
        return tokenToName[index]
    }

    /**
     * Slow Path: Retrieves the [PropertyToken] corresponding to the given property [name].
     *
     * @param name The hierarchical name of the property.
     * @return The property token, or `null` if the name is not found.
     */
    public operator fun get(name: Name): PropertyToken? = nameToToken[name]

    /**
     * Returns the size of the mapping.
     */
    public val size: Int get() = nameToToken.size

    /**
     * Checks if the map is empty.
     */
    public fun isEmpty(): Boolean = nameToToken.isEmpty()

    public companion object {
        /**
         * An empty TokenMap instance.
         */
        public val EMPTY: TokenMap = TokenMap(emptyMap(), emptyArray())

        /**
         * Builds a [TokenMap] from a standard map of names to tokens.
         *
         * This method calculates the required array size based on the maximum token index found in the input.
         *
         * @param mapping Source map linking Names to PropertyTokens.
         * @return An optimized TokenMap instance.
         */
        public fun build(mapping: Map<Name, PropertyToken>): TokenMap {
            if (mapping.isEmpty()) return EMPTY

            val maxIndex = mapping.values.maxOfOrNull { it.index } ?: -1

            val array = arrayOfNulls<Name>(maxIndex + 1)

            for ((name, token) in mapping) {
                array[token.index] = name
            }

            return TokenMap(mapping, array)
        }
    }
}