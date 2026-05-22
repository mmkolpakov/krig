package space.kscience.krig.api.data

import kotlinx.serialization.Serializable
import space.kscience.dataforge.meta.Meta
import kotlin.time.Instant

/**
 * Observable state captured at [at].
 *
 * [state] is the replay baseline. [capabilitySnapshots] carries opt-in runtime
 * state keyed by capability id.
 */
@Serializable
public data class DeviceSnapshot(
    val at: Instant,
    val state: Meta,
    val capabilitySnapshots: Map<String, Meta> = emptyMap(),
)
