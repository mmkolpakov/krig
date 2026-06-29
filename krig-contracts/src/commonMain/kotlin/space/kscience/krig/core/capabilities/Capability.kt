@file:OptIn(space.kscience.krig.core.InternalKrigApi::class)

package space.kscience.krig.core.capabilities

import kotlinx.coroutines.flow.StateFlow
import space.kscience.attributes.Attribute
import space.kscience.krig.api.lifecycle.LifecycleState
import space.kscience.krig.core.contracts.Device
import space.kscience.krig.core.contracts.CapabilityHost
import space.kscience.dataforge.names.Name

/**
 * Typed key for a [Capability]. Runtime hosts store capabilities by stable [id]; the [Attribute]
 * parent is kept as a typed-key interop surface for integrations that already use attributes-kt
 * stores, not as the SDK's primary capability registry.
 */
public interface CapabilityKey<C : Capability<*>> : Attribute<C> {
    public val id: Name
}

/**
 * Local runtime state owned by a [CapabilityHost].
 *
 * A `PipelineFeatureSpec` is the serializable Manifest declaration. A runtime pipeline feature
 * may translate that declaration into operation policies and capabilities. Use
 * `PipelineFeatureSpec` for serialized descriptions; use `Capability` for live state
 * and coroutines.
 *
 * The generic [S] is the *runtime* state of the capability — typically a holder containing
 * `MutableStateFlow`s, `Mutex`es, accumulators, etc. Capabilities that have nothing to
 * carry can use `Unit`. Capabilities that want their state to survive process death opt in
 * additionally to the storage-aware `Snapshotting` contract with a separate,
 * `@Serializable` snapshot view.
 *
 * Lifecycle hooks receive the owning [CapabilityHost]. Device-specific capabilities can
 * require `host as Device`; application services are requested from the DataForge Context.
 */
public interface Capability<S : Any> {

    public val key: CapabilityKey<*>

    /**
     * Live runtime state. May be `Unit` for purely behavioural capabilities, an aggregate of
     * `MutableStateFlow`s, or any plain mutable holder.
     */
    public val state: S

    /** Called when attached to a host. Subscribe to flows and start jobs here. */
    context(host: CapabilityHost)
    @Suppress("EmptyMethod")
    public suspend fun onAttach() {}

    /** Called on detach / host close. Implementations release resources here. */
    context(host: CapabilityHost)
    @Suppress("EmptyMethod")
    public suspend fun onDetach() {}
}

/**
 * Drives the device through its [LifecycleState] via an FSM. With the capability
 * installed the device starts in [LifecycleState.Detached];
 * without it the pipeline auto-promotes to [LifecycleState.Running].
 *
 * `S = Unit` because lifecycle state lives on the [Device] (`Device.lifecycleState`,
 * `AbstractDevice.lifecycleStateFlow`), not on this capability — the capability only
 * **manages** the device-level state, it doesn't own a parallel copy.
 *
 * [lifecycleStateFlow] is exposed for convenience subscribers — implementations should
 * either delegate it to `device.lifecycleStateFlow` (preferred) or keep a private shadow
 * that is updated **before** `Device.updateLifecycleState` is called, never after.
 */
public interface LifecycleManagingCapability : Capability<Unit> {
    public val lifecycleStateFlow: StateFlow<LifecycleState>
    override val state: Unit get() = Unit
}
