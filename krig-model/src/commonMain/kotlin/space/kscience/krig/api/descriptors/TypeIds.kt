package space.kscience.krig.api.descriptors

import kotlinx.serialization.KSerializer

/** Stable type identifiers used in property descriptors. */
public object TypeIds {
    public const val DOUBLE: String = "kotlin.Double"
    public const val INT: String = "kotlin.Int"
    public const val LONG: String = "kotlin.Long"
    public const val BOOLEAN: String = "kotlin.Boolean"
    public const val STRING: String = "kotlin.String"
    public const val META: String = "space.kscience.dataforge.meta.Meta"
    public const val BYTES: String = "kotlin.ByteArray"
}

/** Type id supplied by kotlinx.serialization metadata. */
public fun typeIdOf(serializer: KSerializer<*>): String = serializer.descriptor.serialName
