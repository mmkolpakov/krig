package space.kscience.controls.api.features

/**
 * A strongly-typed key for a [Feature].
 *
 * This interface is used to retrieve a specific type of feature from a [DeviceBlueprint]
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