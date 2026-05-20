package space.kscience.krig.api.features

import kotlinx.serialization.Polymorphic
import space.kscience.krig.api.annotations.PolymorphicBase
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.MetaRepr

/** Serializable blueprint-level DTO for an installable krig feature. */
@Polymorphic
@PolymorphicBase
public interface FeatureSpec : MetaRepr {
    override fun toMeta(): Meta = Meta.EMPTY
}
