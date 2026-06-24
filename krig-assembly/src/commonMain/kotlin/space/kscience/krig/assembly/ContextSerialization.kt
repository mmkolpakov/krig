package space.kscience.krig.assembly

import kotlinx.serialization.json.Json
import space.kscience.dataforge.context.Context
import space.kscience.krig.api.serialization.SerializationContributor
import space.kscience.krig.api.serialization.krigJson
import space.kscience.krig.api.serialization.krigStorageJson

/**
 * Opt-in bridge: exposes a DataForge [Context]'s aggregated
 * [Context.serializationModule] — the union of installed plugins' `serializerModule`s — as a krig
 * [SerializationContributor].
 *
 * This lets [krigJson]/[krigStorageJson] pick up user DTOs registered by DataForge plugins **without
 * replacing** krig's own wire defaults: krig's polymorphic module stays the base and the context module
 * is layered on top. It is deliberately a separate, explicit call — krig never swaps `krigJson` for
 * `context.json` implicitly.
 */
public fun Context.asSerializationContributor(): SerializationContributor =
    SerializationContributor(serializationModule)

/**
 * [krigJson] augmented with [context]'s plugin serializers (krig defaults remain the base, then the
 * context module, then any [extra] contributors).
 */
public fun krigJson(context: Context, vararg extra: SerializationContributor): Json =
    krigJson(context.asSerializationContributor(), *extra)

/** Compact-storage counterpart of [krigJson] augmented with [context]'s plugin serializers. */
public fun krigStorageJson(context: Context, vararg extra: SerializationContributor): Json =
    krigStorageJson(context.asSerializationContributor(), *extra)
