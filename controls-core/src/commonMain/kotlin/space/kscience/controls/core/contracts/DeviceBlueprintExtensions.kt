package space.kscience.controls.core.contracts

import space.kscience.controls.core.features.Feature
import space.kscience.controls.core.features.FeatureKey

public fun <F : Feature> DeviceBlueprint<*>.require(key: FeatureKey<F>): F {
    return this[key] ?: error("DeviceBlueprint '${id}' requires feature '${key.id}', but it is missing.")
}