package space.kscience.krig.api.descriptors

import kotlin.jvm.JvmInline
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable

/**
 * Stable SDK type identifier for a property/tag value — e.g. `kotlin.Double`. This is a contract id,
 * **not** a runtime reflection name; it stays stable across refactors and platforms.
 *
 * Modelled as an inline [value class][JvmInline] over the canonical [id] string: it is type-safe at
 * the API surface yet serializes inline as that string, so the wire/JSON form is identical to the
 * previous bare-`String` field (no manifest/ABI break at the data level).
 */
@Serializable
@JvmInline
public value class TypeId(public val id: String) {
    init {
        require(id.isNotBlank()) { "TypeId must not be blank" }
    }

    override fun toString(): String = id
}

/** Stable type identifiers used in property and acquisition descriptors. */
public object TypeIds {
    public val DOUBLE: TypeId = TypeId("kotlin.Double")
    public val INT: TypeId = TypeId("kotlin.Int")
    public val LONG: TypeId = TypeId("kotlin.Long")
    public val BOOLEAN: TypeId = TypeId("kotlin.Boolean")
    public val STRING: TypeId = TypeId("kotlin.String")
    public val META: TypeId = TypeId("space.kscience.dataforge.meta.Meta")
    public val BYTES: TypeId = TypeId("kotlin.ByteArray")
}

/** Type id supplied by kotlinx.serialization metadata. */
public fun typeIdOf(serializer: KSerializer<*>): TypeId = TypeId(serializer.descriptor.serialName)
