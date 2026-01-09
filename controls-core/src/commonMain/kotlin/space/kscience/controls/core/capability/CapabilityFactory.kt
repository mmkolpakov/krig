package space.kscience.controls.core.capability

import space.kscience.dataforge.meta.Meta

/**
 * A factory responsible for creating [Capability] instances.
 * Registered in the [space.kscience.controls.core.bundle.FeatureRegistry].
 */
public interface CapabilityFactory {
    /**
     * The unique capability type ID (e.g. "controls.transport.mqtt").
     * Must match [space.kscience.controls.api.meta.FeatureSpec.capabilityType].
     */
    public val id: String

    /**
     * Creates a new instance of the capability.
     *
     * @param context The secure sandbox context for the new capability.
     * @param config The initial configuration [Meta] (serialized [space.kscience.controls.api.meta.FeatureSpec]).
     * @return A new, unattached [Capability] instance.
     */
    public fun create(context: CapabilityContext, config: Meta): Capability
}