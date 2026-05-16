package space.kscience.krig.core.contracts

import space.kscience.krig.core.InternalKrigApi

/** Tracks in-flight operations for graceful shutdown coordination. */
@InternalKrigApi
public interface OperationTracker {
    public fun enterOperation()
    public fun exitOperation()
}
