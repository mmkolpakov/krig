package space.kscience.controls.core.features

import space.kscience.controls.api.features.Feature
import space.kscience.controls.api.features.FeatureKey

/**
 * Base class for Runtime/Logic features that are not serialized and live only in memory.
 */
public abstract class RuntimeFeature(
    override val key: FeatureKey<*>,
    override val capability: String
) : Feature