package space.kscience.krig.core.capabilities

import kotlinx.coroutines.flow.StateFlow
import space.kscience.attributes.Attribute
import space.kscience.krig.api.lifecycle.LifecycleState
import space.kscience.krig.core.contracts.Device

/**
 * Typed key for a [DeviceCapability]. The dual generic — [C] for the capability type and
 * [S] for its runtime-state type — lets stores and registries reason about the snapshot
 * shape (`Snapshotting<Snap>`, persistence, replay) without re-flecting over [C].
 *
 * [id] is the stable serialised identifier when a capability descriptor travels over the
 * wire. Companion `Key` objects on capability interfaces should be `object`s implementing
 * `CapabilityKey<TheCapability, TheStateType>`.
 */
public interface CapabilityKey<C : DeviceCapability<S>, S : Any> : Attribute<C> {
    public val id: String
}

/**
 * Runtime object providing a composable API domain (lifecycle, automation, streaming, ...).
 * Companion to the serializable [DeviceFeatureSpec][space.kscience.krig.api.features.DeviceFeatureSpec] DTO:
 * the blueprint declares the `DeviceFeatureSpec`, the runtime installs the matching `DeviceCapability`
 * via contributed feature installers. Use `DeviceFeatureSpec`
 * for wire-traversable descriptions; use `DeviceCapability` for live state and coroutines.
 *
 * The generic [S] is the *runtime* state of the capability — typically a holder containing
 * `MutableStateFlow`s, `Mutex`es, accumulators, etc. Capabilities that have nothing to
 * carry can use `Unit`. Capabilities that want their state to survive process death opt in
 * additionally to [Snapshotting][space.kscience.krig.api.data.Snapshotting] with a
 * separate, `@Serializable` snapshot view.
 *
 * Lifecycle hooks accept the owning [Device] as a `context(device: Device)` parameter, so
 * implementors can write `messageFlow.collect { … }` or `clock.now()` with no `device.`
 * prefix.
 */
public interface DeviceCapability<S : Any> {

    public val key: CapabilityKey<*, S>

    /**
     * Live runtime state. May be `Unit` for purely behavioural capabilities (e.g. legacy
     * lifecycle), an aggregate of `MutableStateFlow`s, or any plain mutable holder.
     */
    public val state: S

    /** Called when attached to the ambient `device`. Subscribe to properties, start jobs here. */
    context(device: Device)
    public suspend fun onAttach() {}

    /** Called on detach / device close. Implementations release resources here. */
    context(device: Device)
    public suspend fun onDetach() {}
}

/**
 * **Lifecycle-managed role marker.** Drives the device through its [LifecycleState] via
 * an FSM. With the capability installed the device starts in [LifecycleState.Detached];
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
public interface LifecycleManagingCapability : DeviceCapability<Unit> {
    public val lifecycleStateFlow: StateFlow<LifecycleState>
    override val state: Unit get() = Unit
}
