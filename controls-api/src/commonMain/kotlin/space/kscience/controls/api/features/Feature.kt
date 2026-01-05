package space.kscience.controls.api.features

import kotlinx.serialization.Polymorphic
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.MetaRepr

/**
 * A base interface for a Feature descriptor. A feature provides structured, serializable metadata
 * about a specific capability of a device (e.g., FSM, Telemetry, Connectivity).
 *
 * This is an open, non-sealed interface annotated with `@Polymorphic` to allow users of the library
 * to define their own custom features in separate modules.
 */
@Polymorphic
public interface Feature : MetaRepr {
    /**
     * A reference to the type-safe key for this feature instance.
     * This ensures that any instance of a feature can be mapped back to its definition.
     */
    public val key: FeatureKey<*>

    /**
     * A fully qualified name of the capability interface this feature describes.
     * For example, `space.kscience.controls.core.contracts.Device`.
     */
    public val capability: String

    override fun toMeta(): Meta = Meta.EMPTY
}
