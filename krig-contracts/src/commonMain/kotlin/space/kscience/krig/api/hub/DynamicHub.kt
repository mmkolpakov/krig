package space.kscience.krig.api.hub

import kotlinx.coroutines.flow.Flow
import space.kscience.krig.api.messages.DeviceDepartureReason
import space.kscience.krig.core.UnstableKrigForSubclassing
import space.kscience.krig.core.contracts.Device
import space.kscience.krig.core.hook.HookRegistry
import space.kscience.dataforge.names.Name

/**
 * A [Device] whose children can be attached / detached / replaced at runtime. Separate from
 * [Device] itself because most devices are leaves — exposing mutation universally would
 * weaken encapsulation.
 */
@SubclassOptInRequired(UnstableKrigForSubclassing::class)
public interface DynamicHub : Device {

    /**
     * Attach [device] under [name]. Throws [HubConflictException] if [name] is already taken —
     * use [replace] for idempotent overwrite. Emits [HubEvent.Attached].
     */
    public suspend fun attach(name: Name, device: Device)

    /**
     * Atomically attaches every [devices] entry, or leaves topology unchanged on failure.
     * Ownership transfers to the hub only after this function returns normally.
     */
    public suspend fun attachAll(devices: Map<Name, Device>)

    /**
     * Attach [device] under [name], replacing whatever was there. Returns the previous device
     * (if any). When [closePrevious] is `true` (default — fail-safe-by-default for owned
     * resources like sockets / serial ports), the previous device is closed *after* the swap;
     * `close()` exceptions are swallowed so the swap itself is atomic. Pass
     * `closePrevious = false` to transfer ownership to the caller. Emits [HubEvent.Replaced].
     */
    public suspend fun replace(
        name: Name,
        device: Device,
        closePrevious: Boolean = true,
    ): Device?

    /**
     * Detach the device under [name]. Cascades `shutdown()`. Emits [HubEvent.Detached] with
     * [reason]. Returns the detached device or `null` if absent (idempotent).
     */
    public suspend fun detach(
        name: Name,
        reason: DeviceDepartureReason = DeviceDepartureReason.Graceful,
    ): Device?

    /**
     * Hot observability flow of topology events. This is not a durable log; use
     * [children] or [childrenFlow] as the source of truth for current topology.
     */
    public val hubEvents: Flow<HubEvent>

    /**
     * Topology-scope [HookRegistry] — carries handlers for [space.kscience.krig.core.hook.DeviceAttached]
     * and [space.kscience.krig.core.hook.DeviceDetached]. Operation-pipeline hooks live on
     * the device's typed pipeline builder instead. Registering such hooks here is a no-op.
     */
    public val hubHooks: HookRegistry
}

/** Thrown by [DynamicHub.attach] when the target name is already taken. */
public class HubConflictException(
    public val conflictName: Name,
    public val reason: String,
) : Exception("Hub attach conflict for '$conflictName': $reason")
