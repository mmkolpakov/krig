package space.kscience.krig.api.messages

import space.kscience.krig.api.context.ExecutionContext

/**
 * Ingress bridge: lifts the wire-level [RequestMessage.callerIdentity] into an [ExecutionContext]
 * so the runtime pipeline can resolve it to a typed `Principal` (via an identity provider) before
 * authorization. Transport adapters that route incoming requests into the local runtime are expected
 * to install the result into the coroutine context before invoking a device operation.
 *
 * [base] lets a caller preserve an already-established context (correlation id, origin device,
 * attributes); only [ExecutionContext.callerIdentity] is overlaid. An already-resolved
 * [ExecutionContext.principal] in [base] still wins downstream — the identity provider is consulted
 * only when the principal is anonymous.
 */
public fun RequestMessage.executionContext(base: ExecutionContext = ExecutionContext()): ExecutionContext =
    base.copy(callerIdentity = callerIdentity)
