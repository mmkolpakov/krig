package space.kscience.magix.api

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter

/** What part of an authorized subscription may be pushed into the Magix endpoint/broker. */
public enum class MagixSubscriptionPushdown {
    /** Subscribe broadly and enforce filtering/authorization in this process. */
    None,

    /** Push only [MagixMessageFilter] to the endpoint; authorization still runs locally. */
    MessageFilter,
}

/**
 * Executable subscription plan for Magix streams that need both broker filtering and local
 * authorization. Pushdown is an optimization, not the security boundary: [localFilter] is always
 * applied before [authorize].
 */
public data class AuthorizedMagixSubscriptionPlan(
    public val messageFilter: MagixMessageFilter = MagixMessageFilter.ALL,
    public val pushdown: MagixSubscriptionPushdown = MagixSubscriptionPushdown.None,
) {
    public val brokerFilter: MagixMessageFilter
        get() = when (pushdown) {
            MagixSubscriptionPushdown.None -> MagixMessageFilter.ALL
            MagixSubscriptionPushdown.MessageFilter -> messageFilter
        }

    public val localFilter: MagixMessageFilter get() = messageFilter
}

/**
 * Subscribes with a local [filter], then applies an asynchronous per-message [authorize] gate: a
 * message is delivered only when [authorize] returns `true`. Use the [AuthorizedMagixSubscriptionPlan]
 * overload when a transport can safely push the message filter to a broker.
 *
 * The authorization domain is intentionally kept out of this transport module: wire it at the call site,
 * deriving the principal from [MagixMessage.user] and the permission from [MagixMessage.sourceEndpoint], e.g.
 * ```
 * endpoint.authorizedSubscribe { message ->
 *     try {
 *         authService.checkPermission(
 *             principalDecoder(message.user),
 *             ControlsPermission.DeviceSubscribe(message.sourceEndpoint.toString()),
 *         )
 *         true
 *     } catch (denied: AuthorizationException) {
 *         false
 *     }
 * }
 * ```
 */
public fun MagixEndpoint.authorizedSubscribe(
    filter: MagixMessageFilter = MagixMessageFilter.ALL,
    authorize: suspend (MagixMessage) -> Boolean,
): Flow<MagixMessage> = authorizedSubscribe(
    plan = AuthorizedMagixSubscriptionPlan(messageFilter = filter),
    authorize = authorize,
)

public fun MagixEndpoint.authorizedSubscribe(
    plan: AuthorizedMagixSubscriptionPlan,
    authorize: suspend (MagixMessage) -> Boolean,
): Flow<MagixMessage> = subscribe(plan.brokerFilter)
    .filter(plan.localFilter)
    .filter(authorize)
