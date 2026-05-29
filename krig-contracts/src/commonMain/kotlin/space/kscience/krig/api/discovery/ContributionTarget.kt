package space.kscience.krig.api.discovery

import kotlin.reflect.KClass

/**
 * Typed discovery target over DataForge's string `target`.
 *
 * [type] lets `Context.gather(target)` keep DataForge runtime type checks instead
 * of relying on unchecked map casts.
 */
public data class ContributionTarget<T : Any>(
    public val id: String,
    public val type: KClass<out T>,
)

public inline fun <reified T : Any> ContributionTarget(id: String): ContributionTarget<T> =
    ContributionTarget(id, T::class)
