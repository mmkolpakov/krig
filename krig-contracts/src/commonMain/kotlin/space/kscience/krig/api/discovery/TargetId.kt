package space.kscience.krig.api.discovery

/**
 * Wire target id of a contribution anchor. Read by KSP to route `@Contributes(anchor)`
 * contributors; runtime `ContributionTarget(id)` on the same anchor must match.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
public annotation class TargetId(public val value: String)
