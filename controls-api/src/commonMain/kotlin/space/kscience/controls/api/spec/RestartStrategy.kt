package space.kscience.controls.api.spec

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import space.kscience.controls.api.meta.serializableToMeta
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.MetaRepr
import kotlin.time.Duration

/**
 * Defines a strategy for calculating the delay between restart attempts.
 */
@Serializable
public sealed interface RestartStrategy : MetaRepr {

    @Serializable
    @SerialName("linear")
    public data class Linear(val baseDelay: Duration) : RestartStrategy {
        override fun toMeta(): Meta = serializableToMeta(serializer(), this)
    }

    @Serializable
    @SerialName("exponential")
    public data class ExponentialBackoff(val baseDelay: Duration) : RestartStrategy {
        override fun toMeta(): Meta = serializableToMeta(serializer(), this)
    }

    @Serializable
    @SerialName("fibonacci")
    public data class Fibonacci(val baseDelay: Duration) : RestartStrategy {
        override fun toMeta(): Meta = serializableToMeta(serializer(), this)
    }
}