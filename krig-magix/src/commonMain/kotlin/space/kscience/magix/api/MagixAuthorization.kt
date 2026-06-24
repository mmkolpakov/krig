package space.kscience.magix.api

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter

/**
 * Subscribes with the server/broker-side [filter], then applies an asynchronous per-message [authorize]
 * gate: a message is delivered only when [authorize] returns `true`. This is the building block for RBAC
 * on the bus — a denied message is silently dropped rather than throwing into the consuming flow.
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
): Flow<MagixMessage> = subscribe(filter).filter(authorize)
