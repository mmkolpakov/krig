package space.kscience.krig.api.annotations

/**
 * Marks a FeatureSpec DTO with its stable runtime [id].
 *
 * KSP `FeatureSpecContractValidator` enforces that [id] matches `@SerialName`
 * and that the DTO exposes a companion `ID` constant for runtime Feature code to reference.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
public annotation class KrigFeatureSpec(public val id: String)
