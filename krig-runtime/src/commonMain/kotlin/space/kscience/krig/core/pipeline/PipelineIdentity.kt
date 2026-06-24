package space.kscience.krig.core.pipeline

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext
import space.kscience.krig.api.context.AnonymousPrincipal
import space.kscience.krig.api.context.Principal
import space.kscience.krig.api.context.executionContext
import space.kscience.krig.api.services.IdentityProvider

/**
 * Per-operation memo of the resolved [Principal]. Installed once at an operation/batch boundary so
 * the several principal-reading gates and observers of one operation — and every member of a batch —
 * resolve identity once instead of per property (the N+1 resolve). The holder is read and written by
 * a single operation coroutine, sequentially, so a plain field is sufficient.
 */
internal class ResolvedPrincipalCache : AbstractCoroutineContextElement(Key) {
    var value: Principal? = null

    companion object Key : CoroutineContext.Key<ResolvedPrincipalCache>
}

/**
 * Resolves the caller [Principal] for the current operation, honoring an already-resolved
 * [ResolvedPrincipalCache] when present. The first resolution within a boundary populates the cache;
 * subsequent gate/observer lookups reuse it.
 */
internal suspend fun currentPipelinePrincipal(identityProvider: IdentityProvider): Principal {
    val cache = currentCoroutineContext()[ResolvedPrincipalCache]
    cache?.value?.let { return it }
    val context = currentCoroutineContext().executionContext ?: return AnonymousPrincipal
    val principal = context.principal
    val resolved = if (principal != AnonymousPrincipal) principal else identityProvider.resolve(context.callerIdentity)
    cache?.value = resolved
    return resolved
}
