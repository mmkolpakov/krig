package space.kscience.krig.api.data

import kotlinx.serialization.KSerializer

/**
 * Optional capability for taking a serialisable snapshot of a runtime object.
 *
 * The split between *runtime state* (what a
 * [DeviceCapability][space.kscience.krig.core.capabilities.DeviceCapability] holds for
 * local execution: `MutableStateFlow`s, `Mutex`es, `Job`s) and a *snapshot view* is a
 * deliberate design choice. Implementing this interface signals: «my mutable runtime state
 * can be projected onto a `@Serializable Snap` and reconstructed from one». Capabilities
 * that do not implement [Snapshotting] are silently skipped by snapshot stores —
 * capability authors opt in to cross-process persistence on a case-by-case basis, exactly
 * because not every aspect of a device makes sense to serialise.
 *
 * Contract:
 * - [Snap] **must** be marked `@Serializable` from `kotlinx.serialization`. The runtime
 *   uses [snapshotSerializer] to (de)serialise.
 * - [captureSnapshot] is non-suspending — it must not perform I/O. Reading a
 *   `MutableStateFlow.value` and mapping to a serialisable record is the canonical shape.
 * - [restoreSnapshot] is suspending — it may need to drive the device to the snapshot
 *   state through public mutators (which themselves may suspend on coroutine sync
 *   primitives or device I/O).
 *
 * Industrial precedent: this mirrors arkivanov/Decompose's `StateKeeper` (serialisable,
 * survives process death) sitting alongside `InstanceKeeper` (runtime, survives
 * configuration change).
 */
public interface Snapshotting<Snap : Any> {
    /**
     * `KSerializer<Snap>` exposed for the runtime to (de)serialise. The capability author
     * supplies this directly — typically `serializer()` on the companion of the
     * `@Serializable Snap` type. Inlining the serializer into the interface (rather than
     * resolving it reflectively) keeps the SDK off the reflection path on every snapshot.
     */
    public val snapshotSerializer: KSerializer<Snap>

    /**
     * Take a snapshot of the current runtime state. Pure projection — no I/O, no
     * suspension. The returned [Snap] **must** be a `@Serializable` type.
     */
    public fun captureSnapshot(): Snap

    /**
     * Replace the runtime state with the contents of [snap]. May suspend if restoring
     * involves driving the device through public mutators.
     */
    public suspend fun restoreSnapshot(snap: Snap)
}
