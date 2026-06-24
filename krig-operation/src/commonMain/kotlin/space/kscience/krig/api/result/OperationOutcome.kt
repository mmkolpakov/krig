@file:MustUseReturnValues

package space.kscience.krig.api.result

import kotlinx.coroutines.CancellationException
import kotlinx.io.IOException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import space.kscience.dataforge.meta.toMeta
import space.kscience.krig.api.faults.OperationFault
import space.kscience.krig.api.faults.OperationFaultException
import space.kscience.krig.api.faults.SerializableOperationFailure
import space.kscience.krig.api.faults.TransportFault
import space.kscience.krig.api.faults.toSerializableOperationFailure

/**
 * Result of an operation: [Ok] carries the value, [Fail] carries a [OperationFault].
 *
 * Core code should pass predictable operation faults as values. Use
 * [runCatchingOperation] only at interop boundaries where user code, drivers, or legacy
 * accessors may still throw.
 */
@Serializable
public sealed interface OperationOutcome<out T> {
    @Serializable
    public data class Ok<out T>(public val value: T) : OperationOutcome<T>

    @Serializable
    public data class Fail(public val fault: OperationFault) : OperationOutcome<Nothing>

    public companion object {
        /** Shared `Ok(Unit)` — avoids an allocation on the common write / execute return path. */
        public val OkUnit: Ok<Unit> = Ok(Unit)
    }
}

@PublishedApi
internal val outcomeFailureJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    prettyPrint = false
}

/** Shorthand for [OperationOutcome.Companion.OkUnit]. */
public fun okUnit(): OperationOutcome<Unit> = OperationOutcome.OkUnit

/** Shorthand for [OperationOutcome.Ok]. */
public fun <T> ok(value: T): OperationOutcome<T> = OperationOutcome.Ok(value)

/** Shorthand for [OperationOutcome.Fail]. */
public fun fail(fault: OperationFault): OperationOutcome<Nothing> = OperationOutcome.Fail(fault)

/** Converts this fault into a typed [OperationOutcome.Fail]. */
public fun <T> OperationFault.toOutcome(): OperationOutcome<T> = OperationOutcome.Fail(this)

/** Folds an outcome into a single value. */
public inline fun <T, R> OperationOutcome<T>.fold(
    onOk: (T) -> R,
    onFail: (OperationFault) -> R,
): R = when (this) {
    is OperationOutcome.Ok -> onOk(value)
    is OperationOutcome.Fail -> onFail(fault)
}

/** Extracts the success value or throws [OperationFaultException] wrapping the fault. */
public fun <T> OperationOutcome<T>.getOrThrow(): T = when (this) {
    is OperationOutcome.Ok -> value
    is OperationOutcome.Fail -> throw OperationFaultException(fault)
}

/** Extracts the success value, or applies [fallback] to the fault. */
public inline fun <T> OperationOutcome<T>.getOrElse(fallback: (OperationFault) -> T): T = when (this) {
    is OperationOutcome.Ok -> value
    is OperationOutcome.Fail -> fallback(fault)
}

/** Extracts the success value, or `null` on failure. */
public fun <T> OperationOutcome<T>.getOrNull(): T? = when (this) {
    is OperationOutcome.Ok -> value
    is OperationOutcome.Fail -> null
}

/** Extracts the fault, or `null` on success. */
public fun OperationOutcome<*>.faultOrNull(): OperationFault? = when (this) {
    is OperationOutcome.Ok -> null
    is OperationOutcome.Fail -> fault
}

public inline fun <T> OperationOutcome<T>.onSuccess(action: (T) -> Unit): OperationOutcome<T> {
    if (this is OperationOutcome.Ok) action(value)
    return this
}

public inline fun <T> OperationOutcome<T>.onFailure(action: (OperationFault) -> Unit): OperationOutcome<T> {
    if (this is OperationOutcome.Fail) action(fault)
    return this
}

public inline fun <T, R> OperationOutcome<T>.map(transform: (T) -> R): OperationOutcome<R> = when (this) {
    is OperationOutcome.Ok -> OperationOutcome.Ok(transform(value))
    is OperationOutcome.Fail -> this
}

public inline fun <T, R> OperationOutcome<T>.flatMap(transform: (T) -> OperationOutcome<R>): OperationOutcome<R> = when (this) {
    is OperationOutcome.Ok -> transform(value)
    is OperationOutcome.Fail -> this
}

public suspend inline fun <T, R> OperationOutcome<T>.mapSuspend(
    crossinline transform: suspend (T) -> R,
): OperationOutcome<R> = when (this) {
    is OperationOutcome.Ok -> OperationOutcome.Ok(transform(value))
    is OperationOutcome.Fail -> this
}

public suspend inline fun <T, R> OperationOutcome<T>.flatMapSuspend(
    crossinline transform: suspend (T) -> OperationOutcome<R>,
): OperationOutcome<R> = when (this) {
    is OperationOutcome.Ok -> transform(value)
    is OperationOutcome.Fail -> this
}

public suspend inline fun OperationOutcome<Unit>.then(
    crossinline next: suspend () -> OperationOutcome<Unit>,
): OperationOutcome<Unit> = when (this) {
    is OperationOutcome.Ok -> next()
    is OperationOutcome.Fail -> this
}

public inline fun <T> OperationOutcome<T>.mapFault(transform: (OperationFault) -> OperationFault): OperationOutcome<T> =
    when (this) {
        is OperationOutcome.Ok -> this
        is OperationOutcome.Fail -> OperationOutcome.Fail(transform(fault))
    }

/** Returns `true` if this is [OperationOutcome.Ok]. */
public fun OperationOutcome<*>.isOk(): Boolean = this is OperationOutcome.Ok

/** Returns `true` if this is [OperationOutcome.Fail]. */
public fun OperationOutcome<*>.isFail(): Boolean = this is OperationOutcome.Fail

/**
 * Runs [block], converting expected operation failures into [OperationOutcome.Fail].
 *
 * This is an adapter for throwing driver/user code. Prefer constructing
 * [OperationOutcome.Fail] directly for predictable SDK-level faults.
 * Cancellation and programming errors propagate.
 */
public inline fun <T> runCatchingOperation(block: () -> T): OperationOutcome<T> = try {
    val result = block()
    @Suppress("UNCHECKED_CAST")
    if (result is Unit) OperationOutcome.OkUnit as OperationOutcome<T> else OperationOutcome.Ok(result)
} catch (ce: CancellationException) {
    throw ce
} catch (e: OperationFaultException) {
    OperationOutcome.Fail(e.fault)
} catch (e: IOException) {
    OperationOutcome.Fail(
        TransportFault(
            causeType = e::class.simpleName ?: "IOException",
            message = e.message ?: "no message",
            details = outcomeFailureJson
                .encodeToJsonElement(
                    SerializableOperationFailure.serializer(),
                    e.toSerializableOperationFailure(includeStackTrace = false),
                )
                .toMeta(),
        ),
    )
}
