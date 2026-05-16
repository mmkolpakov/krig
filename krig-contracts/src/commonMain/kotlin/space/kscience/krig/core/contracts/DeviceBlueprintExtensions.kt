package space.kscience.krig.core.contracts

import space.kscience.krig.api.features.MetadataFeature
import space.kscience.krig.api.meta.MemberTag

/**
 * Type-safe access to the [MetadataFeature] of the blueprint.
 */
public val DeviceBlueprint<*>.metadata: MetadataFeature?
    get() = featureSpec<MetadataFeature>(MetadataFeature.ID)

/**
 * Convenience accessor for tags stored in the metadata DeviceFeatureSpec.
 */
public val DeviceBlueprint<*>.tags: Set<MemberTag>
    get() = metadata?.tags ?: emptySet()

/**
 * Convenience accessor for description stored in the metadata DeviceFeatureSpec.
 */
public val DeviceBlueprint<*>.description: String?
    get() = metadata?.description
