package space.kscience.krig.core.contracts

import space.kscience.krig.api.features.MetadataFeature

/**
 * Type-safe access to the [MetadataFeature] of the blueprint.
 */
public val DeviceBlueprint<*>.metadata: MetadataFeature?
    get() = featureSpec<MetadataFeature>(MetadataFeature.NAME)

/**
 * Convenience accessor for description stored in the metadata FeatureSpec.
 */
public val DeviceBlueprint<*>.description: String?
    get() = metadata?.description
