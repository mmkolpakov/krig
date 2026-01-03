package space.kscience.controls.core.features

import kotlinx.serialization.Polymorphic
import space.kscience.dataforge.meta.MetaRepr

/**
 * A strongly-typed key for a [Feature].
 *
 * This interface is used to retrieve a specific type of feature from a [space.kscience.controls.core.contracts.DeviceBlueprint]
 * without relying on error-prone string constants or unchecked casts.
 *
 * The standard pattern is to implement this interface in the `companion object` of the Feature implementation class.
 *
 * @param F The specific type of the [Feature].
 */
public interface FeatureKey<out F : Feature> {
    /**
     * The unique string identifier for this feature type.
     * This ID is used as the key in the [DeviceBlueprint.features] map.
     * It **must** match the serial name used in polymorphic serialization for consistency.
     */
    public val id: String
}

/**
 * A base interface for a Feature descriptor. A feature provides structured, serializable metadata
 * about a specific capability of a device (e.g., FSM, Telemetry, Connectivity).
 *
 * This is an open, non-sealed interface annotated with `@Polymorphic` to allow users of the library
 * to define their own custom features in separate modules.
 */
@Polymorphic
public interface Feature : MetaRepr {
    /**
     * A reference to the type-safe key for this feature instance.
     * This ensures that any instance of a feature can be mapped back to its definition.
     */
    public val key: FeatureKey<*>

    /**
     * A fully qualified name of the capability interface this feature describes.
     * For example, `space.kscience.controls.core.contracts.Device`.
     */
    public val capability: String
}