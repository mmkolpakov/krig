package space.kscience.krig.api.data

import kotlinx.serialization.Serializable
import space.kscience.dataforge.meta.Meta
import kotlin.time.Instant

/**
 * Event-sourcing capture of a device's observable state at [at]. Used by
 * `Reconstructible.captureSnapshot` / `restoreSnapshot` and by `SnapshotStore` for
 * replay and time travel.
 *
 * [state] carries the device-level snapshot — the "outside world" projection used by
 * Reconstructible / replay scenarios (event-sourced rebuild).
 *
 * [capabilitySnapshots] carries per-capability snapshots from the [Snapshotting]
 * opt-in: `key.id → serialized Snap (encoded as Meta via the capability's `KSerializer<Snap>`)`.
 * Capabilities that don't implement `Snapshotting` are absent from the map; capabilities
 * that do can `restoreSnapshot` from this map after process restart, configuration change,
 * or hot-reload. The aggregate-store shape (single map keyed by capability id) follows the
 * Decompose `StateKeeper`, Android `Bundle`-based `onSaveInstanceState`, and Spring Boot
 * `/actuator` precedents.
 *
 * The two paths cohabit on purpose: Reconstructible answers «how do I rewind a scenario
 * to time X» (event-sourced); Snapshotting answers «how does my capability survive a
 * process restart» (snapshot-based). Akka Persistence makes a similar split between
 * `EventSourcedBehavior` and `DurableStateBehavior`.
 */
@Serializable
public data class DeviceSnapshot(
    val at: Instant,
    val state: Meta,
    val capabilitySnapshots: Map<String, Meta> = emptyMap(),
)
