package space.kscience.controls.common.tokens

import kotlin.jvm.JvmInline

/**
 * A lightweight, zero-allocation identifier for a device property within a [PropertyRegistry].
 *
 * This value class compiles down to a primitive `Int` on the JVM, allowing property addresses
 * to be passed through the call stack (Driver -> Registry -> API) without heap allocations.
 *
 * **Binary Layout (32 bits):**
 * - **Bits 0-23 (Index):** The linear index in the underlying storage array. Max 16,777,215 properties.
 * - **Bits 24-31 (Type):** The storage type identifier. Max 255 types.
 *
 * @property raw The raw integer representation of the token.
 */
@JvmInline
public value class PropertyToken(public val raw: Int) {

    /**
     * Extracts the index part of the token (lower 24 bits).
     * This index is used to access the underlying atomic array.
     */
    public val index: Int get() = raw and INDEX_MASK

    /**
     * Extracts the type ordinal part of the token (upper 8 bits).
     * This corresponds to the `DataType` ordinal or internal storage type ID.
     */
    public val typeOrdinal: Int get() = (raw ushr TYPE_SHIFT) and TYPE_MASK

    /**
     * Checks if the token is structurally valid (non-negative raw value).
     */
    public val isValid: Boolean get() = raw >= 0

    override fun toString(): String = "PropertyToken(type=$typeOrdinal, index=$index)"

    public companion object {
        private const val INDEX_MASK = 0x00FFFFFF
        private const val TYPE_MASK = 0xFF
        private const val TYPE_SHIFT = 24

        /**
         * Represents an invalid or unassigned token.
         */
        public val INVALID: PropertyToken = PropertyToken(-1)

        // --- Standard Storage Types ---

        /**
         * Storage Type: Double-precision floating point values.
         * Mapped to `AtomicDoubleArray`. Used for analog sensors and telemetry.
         */
        public const val TYPE_DOUBLE: Int = 0

        /**
         * Storage Type: 64-bit Integers.
         * Mapped to `AtomicLongArray`. Used for counters, timestamps, large integers.
         * Also serves as the backing store for [Int], [Short], [Byte].
         */
        public const val TYPE_LONG: Int = 1

        /**
         * Storage Type: Boolean values.
         * Mapped to `AtomicBooleanArray` (BitSet over Longs) or `AtomicLongArray` (0/1).
         */
        public const val TYPE_BOOLEAN: Int = 2

        /**
         * Storage Type: Structured Metadata.
         * Mapped to `AtomicReferenceArray<Meta>`.
         * Used for strings, enums, and complex configuration structures.
         * This path involves object allocation and is considered "Slow Path".
         */
        public const val TYPE_META: Int = 3

        /**
         * Creates a [PropertyToken] from its components.
         *
         * @param typeOrdinal The storage type ID (must be in 0..255).
         * @param index The index in the storage array (must be in 0..16,777,215).
         * @throws IllegalArgumentException if arguments are out of bounds.
         */
        public fun create(typeOrdinal: Int, index: Int): PropertyToken {
            require(index in 0..INDEX_MASK) { "Token index $index out of bounds (max 16777215)" }
            require(typeOrdinal in 0..TYPE_MASK) { "Token type ordinal $typeOrdinal out of bounds (max 255)" }
            return PropertyToken((typeOrdinal shl TYPE_SHIFT) or index)
        }
    }
}