package space.kscience.krig.core.contracts

import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.sample
import space.kscience.krig.api.context.Principal
import space.kscience.krig.api.messages.DeviceMessage
import space.kscience.krig.api.messages.MessageEnvelope
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Consumer-side shaping options for [Device.subscribe]. Source-side overrides are an
 * integration concern; the SDK applies these options as Flow operators on the returned
 * stream — no extra round-trip to the backend.
 *
 * @property maxRateHz Optional upper bound on per-tag event rate, applied via
 *                    [kotlinx.coroutines.flow.sample]. `null` leaves the stream unsampled.
 * @property typeFilter Optional whitelist of concrete [DeviceMessage] subclasses. Empty =
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
@OptIn(FlowPreview::class)
public suspend fun Device.subscribe(
    principal: Principal,
    options: SubscribeOptions,
): Flow<MessageEnvelope<DeviceMessage>> {
    val base = subscribe(principal)
    if (options === SubscribeOptions.Unthrottled) return base

    var shaped = base
    options.maxRateHz?.let { hz ->
        require(hz > 0.0) { "maxRateHz must be positive, got $hz" }
        val period: Duration = (1.0 / hz).seconds
        shaped = shaped.sample(period)
    }
    if (options.typeFilter.isNotEmpty()) {
        val allowed = options.typeFilter
        shaped = shaped.filter { it.payload::class.simpleName in allowed }
    }
    return shaped
}
