package space.kscience.controls.core

/**
 * Marks declarations that are internal to the controls-composite framework architecture.
 * These APIs are public for technical reasons (cross-module access between core, runtime, and features)
 * but are NOT intended for end-users or driver developers.
 */
@RequiresOptIn("This is an internal API for the controls-composite framework.", RequiresOptIn.Level.WARNING)
@Retention(AnnotationRetention.BINARY)
public annotation class InternalControlsApi