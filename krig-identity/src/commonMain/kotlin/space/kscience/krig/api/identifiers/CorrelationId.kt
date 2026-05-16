package space.kscience.krig.api.identifiers

import kotlinx.serialization.Serializable
import kotlin.coroutines.CoroutineContext
import kotlin.jvm.JvmInline

/**
 * A type-safe, serializable CoroutineContext element to carry a unique correlation ID for tracing a request
 * through different components and asynchronous boundaries.
 *
 * @property id The string value of the correlation identifier.
 */
@JvmInline
@Serializable
public value class CorrelationId(public val id: String) : CoroutineContext.Element {
    override val key: CoroutineContext.Key<*> get() = Key
    public companion object Key : CoroutineContext.Key<CorrelationId> {
        /**
         * Explicit sentinel for execution flows that do not participate in distributed tracing.
         * Runtime layers that need a real trace id should pass one deliberately.
         *
         * The sentinel is intentionally grep-able in logs. It should be mapped to `null`
         * when crossing nullable wire fields.
         */
        public val Unspecified: CorrelationId = CorrelationId("@unspecified")

        /** Restores a typed id from a nullable wire value. Sentinels and blanks mean "no correlation". */
        public fun fromWire(value: String?): CorrelationId? =
            value?.takeIf { it.isNotBlank() && it != Unspecified.id }?.let(::CorrelationId)
    }
    override fun toString(): String = id
}

/** `true` when this value is a real trace/correlation identifier rather than the SDK sentinel. */
public val CorrelationId.isSpecified: Boolean
    get() = this != CorrelationId.Unspecified

/** Nullable wire representation: real ids become strings, [CorrelationId.Unspecified] becomes `null`. */
public val CorrelationId.wireValue: String?
    get() = id.takeIf { isSpecified }
