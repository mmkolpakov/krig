package space.kscience.controls.api.spec

import kotlinx.serialization.Serializable
import space.kscience.controls.common.meta.serializableToMeta
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.MetaRepr
import space.kscience.dataforge.names.Name
import kotlin.time.Duration

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