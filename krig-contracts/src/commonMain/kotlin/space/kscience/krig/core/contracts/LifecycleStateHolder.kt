package space.kscience.krig.core.contracts

import kotlinx.coroutines.flow.StateFlow
import space.kscience.krig.api.lifecycle.LifecycleState
import space.kscience.krig.core.InternalKrigApi

/**
 * Framework-internal mutator for `Device.lifecycleState`. Implemented by [AbstractDevice];
 * operation pipeline wrappers forward it through interface delegation.
 */
@InternalKrigApi
public interface LifecycleStateHolder {
    public val lifecycleStateFlow: StateFlow<LifecycleState>?
        get() = null

    public fun updateLifecycleState(state: LifecycleState)
}
