package space.kscience.controls.core.contracts

import space.kscience.controls.api.features.Feature
import space.kscience.controls.api.features.MetadataFeature
import space.kscience.controls.api.meta.MemberTag
import space.kscience.controls.core.capabilities.MetadataSpec
import space.kscience.controls.core.features.FeatureSpec

/**
 * Access to the [MetadataFeature] DTO of the blueprint using the typed [MetadataSpec].
 */
public val DeviceBlueprint<*>.metadata: MetadataFeature?
    get() = this[MetadataSpec]

/**
 * Convenience accessor for tags stored in the metadata feature.
 */
public val DeviceBlueprint<*>.tags: Set<MemberTag>
    get() = metadata?.tags ?: emptySet()

/**
 * Convenience accessor for description stored in the metadata feature.
 */
public val DeviceBlueprint<*>.description: String?
    get() = metadata?.description

/**
 * Requires a feature to be present in the blueprint or throws an exception.
 *
 * @param spec The typed specification of the feature.
 * @return The feature configuration DTO.
 */
public fun <D : Device, F : Feature> DeviceBlueprint<D>.require(
    spec: FeatureSpec<F, *>
): F {
    return this[spec] ?: error("DeviceBlueprint '${id}' requires feature '${spec.id}', but it is missing.")
}