package space.kscience.controls.api.composition

import kotlinx.serialization.Polymorphic
import space.kscience.controls.api.features.Feature
import space.kscience.controls.api.identifiers.BlueprintId
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.MetaRepr

/**
 * Represents the configuration for a child device within a composite device.
 */
@Polymorphic
public interface ChildComponentConfig : MetaRepr {
    public val blueprintId: BlueprintId
    public val blueprintVersion: String
    public val meta: Meta
    public val features: Set<Feature>
}
