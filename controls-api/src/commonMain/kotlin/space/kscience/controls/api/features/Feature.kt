package space.kscience.controls.api.features

import kotlinx.serialization.Polymorphic
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.MetaRepr

/**
 * A base interface for a Feature descriptor. A feature provides structured, serializable metadata
 * about a specific capability of a device (e.g., FSM, Telemetry, Connectivity).
 */
@Polymorphic
public interface Feature : MetaRepr {
    override fun toMeta(): Meta = Meta.EMPTY
}
