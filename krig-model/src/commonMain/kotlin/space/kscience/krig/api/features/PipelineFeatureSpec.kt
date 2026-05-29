package space.kscience.krig.api.features

import kotlinx.serialization.Polymorphic
import space.kscience.krig.api.annotations.PolymorphicBase

/** Serializable manifest-level DTO for an installable pipeline feature. */
@Polymorphic
@PolymorphicBase
public interface PipelineFeatureSpec
