package space.kscience.krig.core.contracts

import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.sample
import space.kscience.dataforge.names.Name
import space.kscience.krig.api.context.Principal
import space.kscience.krig.api.messages.DeviceMessage
import space.kscience.krig.api.messages.DeviceMessageFrame
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Overflow behaviour for a bounded subscription queue (source- or client-side). */
public enum class DiscardPolicy {
    /** Keep the newest samples; drop the oldest on overflow (DDS `KEEP_LAST`, default). */
    KeepLatest,

    /** Keep the oldest queued samples; drop incoming ones on overflow. */
    KeepOldest,
}

/**
 * Subscription shaping options. Source-capable backends (OPC UA, ПЛК) honour these at the source via
 * [DeviceBackend.applySubscribeOptions] and report the actually-applied values as
 * [AppliedSubscribeOptions]; whatever the source does not apply, the SDK shapes client-side as Flow
 * operators on the returned stream.
 *
 * @property maxRateHz Optional upper bound on per-tag event rate (client fallback via
 *                    [kotlinx.coroutines.flow.sample]). `null` leaves the stream unsampled.
 * @property typeFilter Optional whitelist of wire message types ([DeviceMessage.messageType]).
 *                    Empty = pass everything.
 * @property queueSize Optional bounded queue depth. `null` leaves buffering to the consumer.
 * @property discardPolicy Overflow behaviour for [queueSize] (DDS-style KEEP_LAST/KEEP_ALL-ish).
 */
public data class SubscribeOptions(
    public val maxRateHz: Double? = null,
    public val typeFilter: Set<String> = emptySet(),
    public val queueSize: Int? = null,
    public val discardPolicy: DiscardPolicy = DiscardPolicy.KeepLatest,
) {
    public companion object {
        /** No shaping — the raw flow from the device. */
        public val Unthrottled: SubscribeOptions = SubscribeOptions()

        /** Shorthand: rate-limit to [hz] samples / second. */
        public fun rate(hz: Double): SubscribeOptions = SubscribeOptions(maxRateHz = hz)
    }
}

/**
 * The subscription parameters a backend actually applied at the source, mirroring OPC UA's
 * `revisedSamplingInterval`/`revisedQueueSize`. `null` fields mean the source did not constrain that
 * dimension and the SDK applies the requested [SubscribeOptions] value client-side instead.
 */
public data class AppliedSubscribeOptions(
    public val revisedMaxRateHz: Double? = null,
    public val revisedQueueSize: Int? = null,
    public val discardPolicy: DiscardPolicy = DiscardPolicy.KeepLatest,
) {
    public companion object {
        /** Source applied nothing; the SDK shapes the stream entirely client-side. */
        public val ClientSide: AppliedSubscribeOptions = AppliedSubscribeOptions()
    }
}

/**
 * Subscription overload that applies [SubscribeOptions] to the base [Device.subscribe]
 * flow. Backends may override by short-circuiting: check `options.maxRateHz` and sample
 * at source for high-frequency streams. Default is client-side post-processing.
 */
public suspend fun Device.subscribe(
    principal: Principal,
    options: SubscribeOptions,
): Flow<DeviceMessageFrame<DeviceMessage>> = subscribe(principal).shapedBy(options)

/**
 * Property-granular subscription with [SubscribeOptions]. Authorizes [principal] for [property]
 * (per-property ACL with device-wide fallback) and shapes the stream client-side.
 */
public suspend fun Device.subscribe(
    principal: Principal,
    property: Name,
    options: SubscribeOptions,
): Flow<DeviceMessageFrame<DeviceMessage>> = subscribe(principal, property).shapedBy(options)

@OptIn(FlowPreview::class)
private fun Flow<DeviceMessageFrame<DeviceMessage>>.shapedBy(
    options: SubscribeOptions,
): Flow<DeviceMessageFrame<DeviceMessage>> {
    if (options === SubscribeOptions.Unthrottled) return this

    var shaped = this
    options.maxRateHz?.let { hz ->
        require(hz > 0.0) { "maxRateHz must be positive, got $hz" }
        val period: Duration = (1.0 / hz).seconds
        shaped = shaped.sample(period)
    }
    if (options.typeFilter.isNotEmpty()) {
        val allowed = options.typeFilter
        shaped = shaped.filter { it.payload.messageType in allowed }
    }
    options.queueSize?.let { capacity ->
        require(capacity > 0) { "queueSize must be positive, got $capacity" }
        val overflow = when (options.discardPolicy) {
            DiscardPolicy.KeepLatest -> BufferOverflow.DROP_OLDEST
            DiscardPolicy.KeepOldest -> BufferOverflow.DROP_LATEST
        }
        shaped = shaped.buffer(capacity, onBufferOverflow = overflow)
    }
    return shaped
}
