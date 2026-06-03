package space.kscience.krig.core.contracts

import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.sample
import space.kscience.dataforge.names.Name
import space.kscience.krig.api.context.Principal
import space.kscience.krig.api.messages.DeviceMessage
import space.kscience.krig.api.messages.DeviceMessageFrame
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Consumer-side shaping options for [Device.subscribe]. Source-side overrides are an
 * integration concern; the SDK applies these options as Flow operators on the returned
 * stream — no extra round-trip to the backend.
 *
 * @property maxRateHz Optional upper bound on per-tag event rate, applied via
 *                    [kotlinx.coroutines.flow.sample]. `null` leaves the stream unsampled.
 * @property typeFilter Optional whitelist of wire message types
 *                    ([DeviceMessage.messageType] / `DeviceMessageType` constants). Empty =
 *                    pass everything.
 */
public data class SubscribeOptions(
    public val maxRateHz: Double? = null,
    public val typeFilter: Set<String> = emptySet(),
) {
    public companion object {
        /** No shaping — the raw flow from the device. */
        public val Unthrottled: SubscribeOptions = SubscribeOptions()

        /** Shorthand: rate-limit to [hz] samples / second. */
        public fun rate(hz: Double): SubscribeOptions = SubscribeOptions(maxRateHz = hz)
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
    return shaped
}
