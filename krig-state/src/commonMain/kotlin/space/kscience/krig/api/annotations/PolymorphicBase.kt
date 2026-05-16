package space.kscience.krig.api.annotations

/**
 * Marks an interface as a polymorphic base discoverable by the KSP
 * `SerializersModuleGenerator`. Every `@Serializable` concrete class implementing the
 * annotated interface is auto-registered in the generated module.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
public annotation class PolymorphicBase
