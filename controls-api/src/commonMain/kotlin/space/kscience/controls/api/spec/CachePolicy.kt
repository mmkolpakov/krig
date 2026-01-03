package space.kscience.controls.api.spec

import kotlinx.serialization.Serializable
import space.kscience.controls.common.meta.serializableToMeta
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.MetaRepr
import space.kscience.dataforge.names.Name
import kotlin.time.Duration

/**
 * Defines the scope in which a cached action result is considered valid.
 * This allows for fine-grained control over cache sharing in multi-user or multi-hub environments.
 */
@Serializable
public enum class CacheScope {
    /**
     * The cached result is shared across all users and components within a single hub instance.
     * This is the default and most common scope for caching results of idempotent, read-only operations.
     */
    PER_HUB,

    /**
     * The cached result is specific to the [Principal] who initiated the action.
     * This is useful for actions whose results depend on user permissions or other user-specific context.
     * Two different users calling the same action with the same arguments will receive separately cached results.
     */
    PER_PRINCIPAL,

    /**
     * The cached result is shared globally across all hubs connected to a common backend cache
     * (e.g., a distributed cache like Redis). This scope requires a specialized runtime implementation
     * of the cache service and is intended for large-scale distributed systems.
     */
    GLOBAL
}

/**
 * A declarative policy that describes how the result of an idempotent action should be cached.
 * When an [ActionDescriptor] includes this policy,
 * the runtime is expected to cache the action's result according to these rules.
 *
 * @property ttl The Time-To-Live for a cached entry. After this duration has passed since the entry
 *               was created, it is considered stale and will be re-computed on the next request.
 * @property scope The [CacheScope] defining the visibility and sharing of the cached entry.
 * @property invalidationEvents A list of topic-like [Name] patterns. The runtime should subscribe to
 *                              these topics on the message bus. When a message is received on a matching
 *                              topic, the cache entry for the action associated with this policy should
 *                              be invalidated. This provides an event-driven mechanism for cache invalidation.
 *                              The runtime is expected to support wildcard matching (`*` and `**`) for these names.
 */
@Serializable
public data class CachePolicy(
    val ttl: Duration,
    val scope: CacheScope = CacheScope.PER_HUB,
    val invalidationEvents: List<Name> = emptyList()
) : MetaRepr {
    override fun toMeta(): Meta = serializableToMeta(serializer(), this)
}