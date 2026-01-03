package space.kscience.controls.api.spec

import kotlinx.serialization.Serializable
import space.kscience.controls.common.meta.serializableToMeta
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.MetaRepr
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A policy for retrying a failed operation.
 * @property maxAttempts The maximum number of retry attempts. `0` means no retries. A value less than 0 means infinite retries.
 * @property strategy The [RestartStrategy] to use for calculating delays between retries.
 */
@Serializable
public data class RetryPolicy(
    val maxAttempts: Int = 3,
    val strategy: RestartStrategy = RestartStrategy.Linear(1.seconds),
) : MetaRepr {
    override fun toMeta(): Meta = serializableToMeta(serializer(), this)
}

/**
 * A policy for operation timeouts.
 * @property duration The maximum duration to wait for an operation to complete.
 */
@Serializable
public data class TimeoutPolicy(
    val duration: Duration = 30.seconds,
) : MetaRepr {
    override fun toMeta(): Meta = serializableToMeta(serializer(), this)
}


/**
 * A container for resilience policies for peer-to-peer communication or other remote operations.
 * The runtime is responsible for interpreting these policies and applying them (e.g., by wrapping a client proxy).
 *
 * @property retry An optional policy for retrying failed operations.
 * @property timeout An optional policy for timing out long-running operations.
 */
@Serializable
public data class ResiliencePolicy(
    public val retry: RetryPolicy? = null,
    public val timeout: TimeoutPolicy? = null,
) : MetaRepr {
    override fun toMeta(): Meta = serializableToMeta(serializer(), this)
}