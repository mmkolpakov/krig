package space.kscience.controls.connectivity

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import space.kscience.controls.common.meta.serializableToMeta
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.context.Factory
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.MetaRepr

/**
 * A serializable, declarative description of a transformation between property types.
 * This object is stored within a `DeviceBlueprint` and contains all necessary configuration for the transformation.
 * The actual transformation logic is resolved at runtime by a `PropertyTransformerFactory`.
 */
@Serializable
public sealed interface PropertyTransformerDescriptor : MetaRepr {
    /**
     * The unique type identifier for this transformer. It is used by the runtime to find the corresponding
     * [PropertyTransformerFactory] to create the transformation logic.
     */
    public val type: String
}

/**
 * A descriptor for a standard transformer that converts any object to its string representation.
 */
@Serializable
@SerialName("toString")
public object ToStringTransformerDescriptor : PropertyTransformerDescriptor {
    override val type: String get() = "toString"
    override fun toMeta(): Meta = serializableToMeta(serializer(), this)
}

/**
 * A descriptor for a standard transformer that applies a linear transformation (`y = kx + b`) to a numeric value.
 *
 * @property scale The scaling factor (k).
 * @property offset The offset (b).
 */
@Serializable
@SerialName("linear")
public data class LinearTransformDescriptor(val scale: Double, val offset: Double = 0.0) : PropertyTransformerDescriptor {
    override val type: String get() = "linear"
    override fun toMeta(): Meta = serializableToMeta(serializer(), this)
}

/**
 * A contract for the runtime logic that performs a transformation between property types.
 * This is a **non-serializable**, functional interface. Instances are created at runtime by a [PropertyTransformerFactory]
 * based on a [PropertyTransformerDescriptor].
 *
 * @param S The source property type (contravariant).
 * @param T The target property type (covariant).
 */
public fun interface PropertyTransformer<in S, out T> {
    /**
     * Performs the transformation.
     * @param source The value from the source property.
     * @return The transformed value suitable for the target property.
     */
    public suspend fun transform(source: S?): T?
}

/**
 * A factory for creating instances of [PropertyTransformer] logic at runtime.
 * Implementations of this factory should be registered in the context (e.g., as a `Plugin`) to be discoverable by the runtime.
 */
public interface PropertyTransformerFactory : Factory<PropertyTransformer<*, *>> {
    /**
     * The unique type identifier that this factory handles. Must match the `type` field of a [PropertyTransformerDescriptor].
     */
    public val type: String

    /**
     * Creates an instance of [PropertyTransformer] based on the configuration from the descriptor.
     * The provided [meta] is derived from `descriptor.toMeta()`.
     */
    override fun build(context: Context, meta: Meta): PropertyTransformer<*, *>
}