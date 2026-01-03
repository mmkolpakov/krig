package space.kscience.controls.api.identifiers

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
    public companion object Key : CoroutineContext.Key<CorrelationId>
    override fun toString(): String = id
}