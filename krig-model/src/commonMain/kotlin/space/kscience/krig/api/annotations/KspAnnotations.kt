package space.kscience.krig.api.annotations

/**
 * Marks a DeviceFeatureSpec DTO with its stable runtime [id].
 *
 * KSP `DeviceFeatureSpecContractValidator` enforces that [id] matches `@SerialName`
 * and that the DTO exposes a companion `ID` constant for installer code to reference.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
public annotation class KrigFeatureSpec(public val id: String)
