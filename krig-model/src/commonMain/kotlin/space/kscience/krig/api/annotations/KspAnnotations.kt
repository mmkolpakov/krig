package space.kscience.krig.api.annotations

/**
 * Marks a PipelineFeatureSpec DTO with its stable runtime [id].
 *
 * KSP `PipelineFeatureSpecContractValidator` enforces that [id] matches `@SerialName`
 * and that the DTO exposes a companion `ID` constant for runtime pipeline feature code to reference.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
public annotation class KrigPipelineFeatureSpec(public val id: String)
