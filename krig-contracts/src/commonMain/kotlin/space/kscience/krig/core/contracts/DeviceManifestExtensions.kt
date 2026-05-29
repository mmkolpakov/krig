package space.kscience.krig.core.contracts

import space.kscience.krig.api.features.MetadataFeature

/**
 * Type-safe access to the [MetadataFeature] of the Manifest.
 */
public val DeviceManifest.metadata: MetadataFeature?
    get() = featureSpec<MetadataFeature>(MetadataFeature.NAME)

/**
 * Convenience accessor for description stored in the metadata PipelineFeatureSpec.
 */
public val DeviceManifest.description: String?
    get() = metadata?.description
