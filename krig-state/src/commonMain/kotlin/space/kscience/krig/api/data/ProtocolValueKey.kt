package space.kscience.krig.api.data

/**
 * Typed key for protocol-specific values in [RawValue.Ext].
 *
 * Protocol modules define keys as companion constants. The [typeId] is used only
 * for wire serialization; user code uses the key objects for compile-time identity.
 *
 * @param T The Kotlin type this key encodes/decodes.
 * @property typeId Stable wire identifier (`"<protocol>.<type>"`).
 * @property encode Serializes [T] to a [ByteArray].
 * @property decode Deserializes a [ByteArray] back to [T].
 */
public class ProtocolValueKey<T>(
    public val typeId: String,
    public val encode: (T) -> ByteArray,
    public val decode: (ByteArray) -> T,
)

/** Encodes [value] through [key]'s codec; the [typeId] is taken from the key. */
public fun <T> RawValue.Companion.of(key: ProtocolValueKey<T>, value: T): RawValue.Ext =
    RawValue.Ext(value = key.encode(value), typeId = key.typeId)

/**
 * Decodes a [RawValue.Ext] back to [T] through [key]'s codec.
 *
 * @throws IllegalArgumentException if [typeId][RawValue.Ext.typeId] does not match [key]'s [typeId][ProtocolValueKey.typeId].
 */
public fun <T> RawValue.Ext.decode(key: ProtocolValueKey<T>): T {
    require(typeId == key.typeId) {
        "ProtocolValueKey mismatch: expected '${key.typeId}' but Ext carries '$typeId'"
    }
    return key.decode(value)
}
