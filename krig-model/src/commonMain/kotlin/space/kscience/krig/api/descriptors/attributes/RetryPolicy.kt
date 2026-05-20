package space.kscience.krig.api.descriptors.attributes

import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Declarative retry budget for an operation.
 *
 * `maxAttempts = 0` disables retries. Positive values describe retry attempts after
 * the initial call. Backoff is data-only so specs stay serializable.
 */
@Serializable
public data class RetryPolicy(
    public val maxAttempts: Int = 3,
    public val initialDelay: Duration = 100.milliseconds,
    public val maxDelay: Duration = 5_000.milliseconds,
    public val backoffMultiplier: Double = 2.0,
) {
    init {
        require(maxAttempts >= 0) { "RetryPolicy.maxAttempts must be non-negative, got $maxAttempts" }
        require(initialDelay >= Duration.ZERO) { "RetryPolicy.initialDelay must be non-negative, got $initialDelay" }
        require(maxDelay >= Duration.ZERO) { "RetryPolicy.maxDelay must be non-negative, got $maxDelay" }
        require(backoffMultiplier >= 1.0) {
            "RetryPolicy.backoffMultiplier must be at least 1.0, got $backoffMultiplier"
        }
    }

    public companion object {
        /** No retries: single attempt, propagate any fault. */
        public val None: RetryPolicy = RetryPolicy(
            maxAttempts = 0,
            initialDelay = Duration.ZERO,
            maxDelay = Duration.ZERO,
            backoffMultiplier = 1.0,
        )
    }
}
