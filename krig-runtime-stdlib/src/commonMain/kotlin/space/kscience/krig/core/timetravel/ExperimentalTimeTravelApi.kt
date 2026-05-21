package space.kscience.krig.core.timetravel

/**
 * Opt-in marker for the time-travel assembly surface ([enableTimeTravel] and overloads).
 * The underlying primitives ([SnapshotStore], [RecordingReplayLog], [CheckpointStrategy],
 * [runCheckpointing]) are stable; only the assembly shape may still evolve.
 */
@RequiresOptIn(
    message = "enableTimeTravel is experimental; its signature may evolve in the next minor cycle.",
    level = RequiresOptIn.Level.WARNING,
)
@Retention(AnnotationRetention.BINARY)
public annotation class ExperimentalTimeTravelApi
