package space.kscience.controls.common.tokens

import kotlin.jvm.JvmInline

/**
 * Represents a compiled property address in the Fast Path.
 * It encodes both the storage type and the index in the storage array.
 *
 * Layout (32 bits):
 * [31..24] - Type ID (8 bits)
 * [23..0]  - Index (24 bits, up to 16M properties per device)
 */
@JvmInline
public value class PropertyToken(public val raw: Int) {
    public val index: Int get() = raw and 0x00FFFFFF
    public val typeOrdinal: Int get() = (raw ushr 24) and 0xFF

    override fun toString(): String = "Token(type=$typeOrdinal, index=$index)"
}

public object TokenTypes {
    public const val DOUBLE: Int = 0
    public const val LONG: Int = 1
    public const val BOOLEAN: Int = 2 // Stored in IntArray (0/1)
    public const val OBJECT: Int = 3 // Stored in ReferenceArray
}

/**
 * Creates a raw integer token.
 */
public fun createPropertyToken(typeOrdinal: Int, index: Int): PropertyToken {
    require(index in 0..0xFFFFFF) { "Index $index out of bounds (max 16777215)" }
    require(typeOrdinal in 0..0xFF) { "Type ordinal $typeOrdinal out of bounds (max 255)" }
    return PropertyToken((typeOrdinal shl 24) or index)
}