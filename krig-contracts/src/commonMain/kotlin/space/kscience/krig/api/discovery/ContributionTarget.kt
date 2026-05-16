package space.kscience.krig.api.discovery

import kotlin.jvm.JvmInline

/**
 * Typed discovery target — zero-overhead wrapper over DataForge's string `target`.
 * Compile-time type-inferred consumer signatures (see `Context.gather(target)`).
 */
@JvmInline
public value class ContributionTarget<out T : Any>(public val id: String)
