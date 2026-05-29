package space.kscience.krig.core.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.takeWhile
import space.kscience.krig.api.data.Timestamped
import space.kscience.krig.api.messages.DeviceMessage
import space.kscience.krig.api.messages.DeviceMessageEnvelope
import space.kscience.krig.api.messages.PropertyChangedMessage
import space.kscience.krig.api.messages.payloads
import space.kscience.krig.core.InternalKrigApi
import space.kscience.krig.core.contracts.Device
import space.kscience.dataforge.meta.MetaConverter
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.parseAsName
import kotlin.time.Clock
import kotlin.time.Instant

private const val DEFAULT_PROPERTY_HISTORY_MAX_SIZE: Int = 1000

/**
 * Past values of a single device property in `[from, until]`. Cold flow; pass
 * [Instant.DISTANT_FUTURE] as `until` to tail.
 */
public fun interface PropertyHistory<T> {
    public fun flowHistory(from: Instant, until: Instant): Flow<Timestamped<T>>
}

/** Defaults the lower bound to [Instant.DISTANT_PAST] and the upper bound to `clock.now()`. */
public fun <T> PropertyHistory<T>.flowHistory(
    clock: Clock = Clock.System,
): Flow<Timestamped<T>> = flowHistory(Instant.DISTANT_PAST, clock.now())

/**
 * In-memory [PropertyHistory] that tails a device message flow, keeping the last
 * [maxSize] decoded samples. Decode failures are dropped. Persistent backends live
 * in integrations. The default collector is eager because a history should retain
 * samples even while no UI subscriber is attached.
 */
internal class CollectedPropertyHistory<T>(
    scope: CoroutineScope,
    messages: Flow<DeviceMessage>,
    val deviceName: Name,
    val propertyName: Name,
    val converter: MetaConverter<T>,
    val maxSize: Int = DEFAULT_PROPERTY_HISTORY_MAX_SIZE,
    started: SharingStarted = SharingStarted.Eagerly,
) : PropertyHistory<T> {

    init {
        require(maxSize > 0) { "maxSize must be positive, got $maxSize" }
    }

    private val samples: SharedFlow<Timestamped<T>> = messages
        .filterIsInstance<PropertyChangedMessage>()
        .filter { it.sourceDevice == deviceName && it.property == propertyName }
        .map { message ->
            val decoded = converter.readOrNull(message.value) ?: return@map null
            Timestamped(value = decoded, time = message.time)
        }
        .filterNotNull()
        .shareIn(
            scope = scope,
            started = started,
            replay = maxSize,
        )

    override fun flowHistory(from: Instant, until: Instant): Flow<Timestamped<T>> =
        samples
            .dropWhile { it.time < from }
            .takeWhile { it.time <= until }

}

/**
 * Returns a live [PropertyHistory] for [property] on this device, backed by
 * [Device.messageFlow] and scoped to [Device.deviceScope]. Keeps the last
 * [maxSize] decoded samples; decode failures are dropped.
 */
@OptIn(InternalKrigApi::class)
public fun <T> Device.propertyHistory(
    property: Name,
    converter: MetaConverter<T>,
    maxSize: Int = DEFAULT_PROPERTY_HISTORY_MAX_SIZE,
    started: SharingStarted = SharingStarted.Eagerly,
): PropertyHistory<T> = CollectedPropertyHistory(
    deviceScope, messageFlow.payloads(), name, property, converter, maxSize, started,
)

/** String-name overload of [Device.propertyHistory]. */
@OptIn(InternalKrigApi::class)
public fun <T> Device.propertyHistory(
    property: String,
    converter: MetaConverter<T>,
    maxSize: Int = DEFAULT_PROPERTY_HISTORY_MAX_SIZE,
    started: SharingStarted = SharingStarted.Eagerly,
): PropertyHistory<T> = propertyHistory(property.parseAsName(), converter, maxSize, started)

/** Builds a [PropertyHistory] keyed by [propertyName] from a raw message flow. */
public fun <T> collectPropertyHistory(
    scope: CoroutineScope,
    messages: Flow<DeviceMessage>,
    deviceName: Name,
    propertyName: Name,
    converter: MetaConverter<T>,
    maxSize: Int = DEFAULT_PROPERTY_HISTORY_MAX_SIZE,
    started: SharingStarted = SharingStarted.Eagerly,
): PropertyHistory<T> = CollectedPropertyHistory(
    scope, messages, deviceName, propertyName, converter, maxSize, started,
)

/** Envelope-flow variant preserving the device/runtime context for upstream collectors. */
public fun <T> collectEnvelopePropertyHistory(
    scope: CoroutineScope,
    messages: Flow<DeviceMessageEnvelope<DeviceMessage>>,
    deviceName: Name,
    propertyName: Name,
    converter: MetaConverter<T>,
    maxSize: Int = DEFAULT_PROPERTY_HISTORY_MAX_SIZE,
    started: SharingStarted = SharingStarted.Eagerly,
): PropertyHistory<T> = CollectedPropertyHistory(
    scope, messages.payloads(), deviceName, propertyName, converter, maxSize, started,
)

/** String-name overload of [collectPropertyHistory]. */
public fun <T> collectPropertyHistory(
    scope: CoroutineScope,
    messages: Flow<DeviceMessage>,
    deviceName: String,
    propertyName: String,
    converter: MetaConverter<T>,
    maxSize: Int = DEFAULT_PROPERTY_HISTORY_MAX_SIZE,
    started: SharingStarted = SharingStarted.Eagerly,
): PropertyHistory<T> = collectPropertyHistory(
    scope, messages, deviceName.parseAsName(), propertyName.parseAsName(), converter, maxSize, started,
)
