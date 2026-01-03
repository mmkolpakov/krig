package space.kscience.controls.core.contracts

import space.kscience.controls.api.features.Feature
import space.kscience.controls.api.features.FeatureKey
import space.kscience.controls.api.features.MetadataFeature
import space.kscience.controls.api.meta.MemberTag

/**
 * Access to the [MetadataFeature] of the blueprint.
 */
public val DeviceBlueprint<*>.metadata: MetadataFeature?
    get() = this[MetadataFeature]

/**
 * Convenience accessor for tags stored in [MetadataFeature].
 */
public val DeviceBlueprint<*>.tags: Set<MemberTag>
    get() = metadata?.tags ?: emptySet()

/**
 * Convenience accessor for description stored in [MetadataFeature].
 */
public val DeviceBlueprint<*>.description: String?
    get() = metadata?.description

public fun <F : Feature> DeviceBlueprint<*>.require(key: FeatureKey<F>): F {
    return this[key] ?: error("DeviceBlueprint '${id}' requires feature '${key.id}', but it is missing.")
}