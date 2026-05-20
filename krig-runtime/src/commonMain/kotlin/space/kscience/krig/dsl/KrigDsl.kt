package space.kscience.krig.dsl

/**
 * DslMarker for krig DSL builders. Prevents accidental capture of an outer
 * DSL's implicit receiver when nesting builder blocks.
 *
 * Applied to [DeviceBuilder], [DeclarativeDeviceBuilder], [ExplicitDeviceBuilder],
 * and [DeviceGroupBuilder].
 */
@DslMarker
@Target(AnnotationTarget.CLASS, AnnotationTarget.TYPEALIAS, AnnotationTarget.TYPE)
public annotation class KrigDsl
