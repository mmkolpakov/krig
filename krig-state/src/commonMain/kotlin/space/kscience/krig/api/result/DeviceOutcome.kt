@file:MustUseReturnValues

package space.kscience.krig.api.result

import kotlinx.coroutines.CancellationException
import kotlinx.io.IOException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import space.kscience.dataforge.meta.toMeta
import space.kscience.krig.api.faults.*

/**
 * Result of a device operation: [Ok] carries the value, [Fail] carries a [DeviceFault].
 *
 * Core control-plane code should pass predictable device failures as values. Use
 * [runCatchingDevice] only at interop boundaries where user code, drivers, or legacy
 * accessors may still throw.
 */
@Serializable
public sealed interface DeviceOutcome<out T> {
    @Serializable
    public data class Ok<out T>(public val value: T) : DeviceOutcome<T>

    @Serializable
    public data class Fail(public val fault: DeviceFault) : DeviceOutcome<Nothing>

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

/** Shorthand for [DeviceOutcome.Companion.OkUnit]. */
public fun okUnit(): DeviceOutcome<Unit> = DeviceOutcome.OkUnit

/** Shorthand for [DeviceOutcome.Ok]. */
public fun <T> ok(value: T): DeviceOutcome<T> = DeviceOutcome.Ok(value)

/** Shorthand for [DeviceOutcome.Fail]. */
public fun fail(fault: DeviceFault): DeviceOutcome<Nothing> = DeviceOutcome.Fail(fault)

/** Converts this fault into a typed [DeviceOutcome.Fail]. */
public fun <T> DeviceFault.toOutcome(): DeviceOutcome<T> = DeviceOutcome.Fail(this)

/** Folds an outcome into a single value. */
public inline fun <T, R> DeviceOutcome<T>.fold(
    onOk: (T) -> R,
    onFail: (DeviceFault) -> R,
): R = when (this) {
    is DeviceOutcome.Ok -> onOk(value)
    is DeviceOutcome.Fail -> onFail(fault)
}

/** Extracts the success value or throws [DeviceFaultException] wrapping the fault. */
public fun <T> DeviceOutcome<T>.getOrThrow(): T = when (this) {
    is DeviceOutcome.Ok -> value
    is DeviceOutcome.Fail -> throw DeviceFaultException(fault)
}

/** Extracts the success value, or applies [fallback] to the fault. */
public inline fun <T> DeviceOutcome<T>.getOrElse(fallback: (DeviceFault) -> T): T = when (this) {
    is DeviceOutcome.Ok -> value
    is DeviceOutcome.Fail -> fallback(fault)
}

/** Extracts the success value, or `null` on failure. */
public fun <T> DeviceOutcome<T>.getOrNull(): T? = when (this) {
    is DeviceOutcome.Ok -> value
    is DeviceOutcome.Fail -> null
}

/** Extracts the fault, or `null` on success. */
public fun DeviceOutcome<*>.faultOrNull(): DeviceFault? = when (this) {
    is DeviceOutcome.Ok -> null
    is DeviceOutcome.Fail -> fault
}

/** Alias of [getOrElse]. */
public inline fun <T> DeviceOutcome<T>.recover(fallback: (DeviceFault) -> T): T = getOrElse(fallback)

public inline fun <T> DeviceOutcome<T>.onSuccess(action: (T) -> Unit): DeviceOutcome<T> {
    if (this is DeviceOutcome.Ok) action(value)
    return this
}

public inline fun <T> DeviceOutcome<T>.onFailure(action: (DeviceFault) -> Unit): DeviceOutcome<T> {
    if (this is DeviceOutcome.Fail) action(fault)
    return this
}

public inline fun <T, R> DeviceOutcome<T>.map(transform: (T) -> R): DeviceOutcome<R> = when (this) {
    is DeviceOutcome.Ok -> DeviceOutcome.Ok(transform(value))
    is DeviceOutcome.Fail -> this
}

public inline fun <T, R> DeviceOutcome<T>.flatMap(transform: (T) -> DeviceOutcome<R>): DeviceOutcome<R> = when (this) {
    is DeviceOutcome.Ok -> transform(value)
    is DeviceOutcome.Fail -> this
}

public suspend inline fun <T, R> DeviceOutcome<T>.mapSuspend(
    crossinline transform: suspend (T) -> R,
): DeviceOutcome<R> = when (this) {
    is DeviceOutcome.Ok -> DeviceOutcome.Ok(transform(value))
    is DeviceOutcome.Fail -> this
}

public suspend inline fun <T, R> DeviceOutcome<T>.flatMapSuspend(
    crossinline transform: suspend (T) -> DeviceOutcome<R>,
): DeviceOutcome<R> = when (this) {
    is DeviceOutcome.Ok -> transform(value)
    is DeviceOutcome.Fail -> this
}

public suspend inline fun DeviceOutcome<Unit>.then(
    crossinline next: suspend () -> DeviceOutcome<Unit>,
): DeviceOutcome<Unit> = when (this) {
    is DeviceOutcome.Ok -> next()
    is DeviceOutcome.Fail -> this
}

public inline fun <T> DeviceOutcome<T>.mapFault(transform: (DeviceFault) -> DeviceFault): DeviceOutcome<T> =
    when (this) {
        is DeviceOutcome.Ok -> this
        is DeviceOutcome.Fail -> DeviceOutcome.Fail(transform(fault))
    }

/** Returns `true` if this is [DeviceOutcome.Ok]. */
public fun DeviceOutcome<*>.isOk(): Boolean = this is DeviceOutcome.Ok

/** Returns `true` if this is [DeviceOutcome.Fail]. */
public fun DeviceOutcome<*>.isFail(): Boolean = this is DeviceOutcome.Fail

/**
 * Runs [block], converting expected operation failures into [DeviceOutcome.Fail].
 *
 * This is an adapter for throwing driver/user code. Prefer constructing
 * [DeviceOutcome.Fail] directly for predictable SDK-level faults.
 * Cancellation and programming errors propagate.
 */
public inline fun <T> runCatchingDevice(block: () -> T): DeviceOutcome<T> = try {
    val result = block()
    @Suppress("UNCHECKED_CAST")
    if (result is Unit) DeviceOutcome.OkUnit as DeviceOutcome<T> else DeviceOutcome.Ok(result)
} catch (ce: CancellationException) {
    throw ce
} catch (e: DeviceFaultException) {
    DeviceOutcome.Fail(e.fault)
} catch (e: IOException) {
    DeviceOutcome.Fail(
        GenericDeviceFault(
            code = e::class.simpleName ?: "UNKNOWN",
            message = e.message ?: "no message",
            details = outcomeFailureJson
                .encodeToJsonElement(
                    SerializableDeviceFailure.serializer(),
                    e.toSerializableDeviceFailure(includeStackTrace = false),
                )
                .toMeta(),
        ),
    )
}
