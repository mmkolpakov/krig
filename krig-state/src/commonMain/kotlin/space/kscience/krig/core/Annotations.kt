package space.kscience.krig.core

/**
 * Marks declarations that are internal to the project.
 * These APIs may change without notice between releases and are not intended for public use.
 * They are exposed publicly for technical reasons such as inline-function bodies or
 * cross-module access from runtime implementations.
 */
@RequiresOptIn("KRig internal API; not stable.", RequiresOptIn.Level.WARNING)
@Retention(AnnotationRetention.BINARY)
public annotation class InternalKrigApi

/**
 * Marks public APIs whose shape or production guarantees are still being validated.
 * Opt in at the call site to make that choice explicit.
 */
@RequiresOptIn("KRig experimental API; may change before a stable release.", RequiresOptIn.Level.WARNING)
@Retention(AnnotationRetention.BINARY)
public annotation class ExperimentalKrigApi

/**
 * Marker for interfaces whose *use* is stable but whose *subclassing* ties the
 * implementor to KRig ABI evolution. Opt in with `@OptIn` on a concrete class
 * or `@SubclassOptInRequired` on an abstract one.
 */
@RequiresOptIn(
    message = "Subclassing this krig interface requires explicit opt-in. " +
            "Use @OptIn(UnstableKrigForSubclassing::class) on a concrete class " +
            "or @SubclassOptInRequired(UnstableKrigForSubclassing::class) on an abstract one.",
    level = RequiresOptIn.Level.WARNING,
)
@Retention(AnnotationRetention.BINARY)
public annotation class UnstableKrigForSubclassing

/**
 * Marks an API that allocates [Meta][space.kscience.dataforge.meta.Meta] (or otherwise
 * boxes typed primitives) on the hot path. Prefer typed accessors —
 * `TypedReader<T>` / `TypedWriter<T>` / `TypedSampler<T>` and their primitive
 * specialisations (`GenericTypedReader<Double>`, `GenericTypedReader<Int>`, …) — for high-frequency reads.
 */
@RequiresOptIn(
    message = "This API allocates Meta or boxes typed values on the hot path. " +
            "Prefer typed primitive accessors (GenericTypedReader<Double>, GenericTypedReader<Int>, TypedSampler) " +
            "for high-frequency reads. Opt in with @OptIn(PerformancePitfall::class).",
    level = RequiresOptIn.Level.WARNING,
)
@Retention(AnnotationRetention.BINARY)
public annotation class PerformancePitfall
