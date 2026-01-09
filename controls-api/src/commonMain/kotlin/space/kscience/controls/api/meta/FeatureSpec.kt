package space.kscience.controls.api.meta

import space.kscience.dataforge.meta.*

/**
 * The base contract for a Feature Specification (Configuration).
 * A Feature Spec describes the configuration for a specific Capability (e.g., MQTT Transport, PID Controller).
 *
 * This abstract class extends [Scheme] to support the DataForge DSL for building configurations,
 * but it enforces a type system for identifying the feature factory.
 *
 * @param source The initial metadata source.
 */
public abstract class FeatureSpec(
    source: Meta = Meta.EMPTY
) : Scheme(source) {

    /**
     * The unique identifier for the capability type this specification configures.
     * This ID is used by the Runtime (DeviceHub) to resolve the corresponding [space.kscience.controls.core.features.FeatureFactory].
     *
     * Examples: "controls.transport.mqtt", "controls.logic.pid", "controls.core.fsm".
     */
    public abstract val capabilityType: String

    /**
     * Creates a sealed, immutable snapshot of the configuration meta.
     *
     * This method enforces the presence of the [TYPE_KEY] discriminator field
     * in the output Meta. This ensures that when the specification is serialized (e.g., to JSON)
     * or passed to a factory, the type information is preserved and unambiguous.
     *
     * @return The immutable [Meta] representing the complete feature configuration.
     */
    public fun toImmutableMeta(): Meta = Meta {
        update(this@FeatureSpec.meta) // Copy all fields from the mutable scheme
        TYPE_KEY put capabilityType   // Force-inject the type discriminator
    }.seal()

    public companion object {
        /**
         * The standard key used to store the Feature Type discriminator in Meta.
         */
        public const val TYPE_KEY: String = "capabilityType"
    }
}