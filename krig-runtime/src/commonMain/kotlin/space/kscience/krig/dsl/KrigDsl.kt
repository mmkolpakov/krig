package space.kscience.krig.dsl

/**
 * DslMarker for krig DSL builders. Prevents accidental capture of an outer
 * DSL's implicit receiver when nesting builder blocks.
 *
 * Applied to krig builder receivers across runtime modules: single-device builders
 * in `krig-runtime`, and topology/model builders in `krig-runtime-stdlib`.
 */
@DslMarker
@Target(AnnotationTarget.CLASS, AnnotationTarget.TYPEALIAS, AnnotationTarget.TYPE)
public annotation class KrigDsl
