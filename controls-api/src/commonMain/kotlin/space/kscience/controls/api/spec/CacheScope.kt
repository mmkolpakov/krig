package space.kscience.controls.api.spec

import kotlinx.serialization.Serializable

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