package space.kscience.krig.api.discovery

/**
 * Stable identity of a contribution anchor. [value] is the lowercase namespaced wire id used by
 * `ContributionTarget`; [generatedName] is the explicit PascalCase fragment used for the generated
 * `Merged<generatedName>Plugin` type. KSP validates both values and their module-wide uniqueness.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
public annotation class TargetId(
    public val value: String,
    public val generatedName: String,
)
