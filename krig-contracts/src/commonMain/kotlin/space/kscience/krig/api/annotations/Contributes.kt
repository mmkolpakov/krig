package space.kscience.krig.api.annotations

import kotlin.reflect.KClass

/** How a contributor `object` is materialised into the generated plugin entries map. */
public enum class EmissionStrategy {
    /** Register the contributor object as-is: `"id".asName() to MyObject`. Default for most kinds. */
    DIRECT,

    /**
     * Invoke the contributor object as a factory function: `"id".asName() to MyObject()`.
     * Used for blueprint installers whose `object` implements `() -> DeviceBlueprint<*>`.
     */
    INVOKE_AS_FACTORY,
}

/**
 * Generic contribution marker. [anchor] is the `object` or class companion carrying a
 * `@TargetId("...")` annotation; the KSP aggregator emits a matching `Merged<Kind>Plugin`
 * that returns every contributor for that target. Meta-annotatable via
 * `@Contributes(TheirAnchor::class) annotation class ContributesX`.
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.ANNOTATION_CLASS)
@Retention(AnnotationRetention.BINARY)
public annotation class Contributes(
    public val anchor: KClass<*>,
    public val strategy: EmissionStrategy = EmissionStrategy.DIRECT,
)
