package space.kscience.krig.api.hub

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import space.kscience.krig.api.messages.DeviceDepartureReason
import space.kscience.krig.core.UnstableKrigForSubclassing
import space.kscience.krig.core.contracts.Device
import space.kscience.krig.core.contracts.DeviceNode
import space.kscience.dataforge.names.Name

/**
 * Mutation API for a live device topology.
 *
 * Hubs separate topology changes from lifecycle ownership:
 * [release] removes a child without stopping it, [detach] applies the hub's ownership policy,
 * and [decommission] removes and stops the child explicitly.
 */
@SubclassOptInRequired(UnstableKrigForSubclassing::class)
public interface DeviceHub : Device, DeviceNode {

    /** Direct child devices currently attached to this hub. */
    public val devices: Map<Name, Device>

    /** Reactive view of [devices]. */
    public val devicesFlow: StateFlow<Map<Name, Device>>

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
     * Remove the device under [name] and apply the hub's ownership policy. Default hubs stop
     * owned children and leave shared children running. Emits [HubEvent.Detached] with [reason].
     * Returns the detached device or `null` if absent.
     */
    public suspend fun detach(
        name: Name,
        reason: DeviceDepartureReason = DeviceDepartureReason.Graceful,
    ): Device?

    /**
     * Remove the device under [name] without stopping it. Use this when ownership is handed back
     * to the caller or to another topology. Emits [HubEvent.Detached] with [reason].
     */
    public suspend fun release(
        name: Name,
        reason: DeviceDepartureReason = DeviceDepartureReason.Released,
    ): Device?

    /**
     * Remove the device under [name] and stop it regardless of whether the hub owns it. Use this
     * for explicit disposal of a child endpoint. Emits [HubEvent.Detached] with [reason].
     */
    public suspend fun decommission(
        name: Name,
        reason: DeviceDepartureReason = DeviceDepartureReason.Decommissioned,
    ): Device?

    /**
     * Move the device under [name] into [target] under [targetName] without stopping it.
     * Returns the transferred device or `null` if the source name is absent.
     */
    public suspend fun transfer(
        name: Name,
        target: DeviceHub,
        targetName: Name = name,
    ): Device?

    /** Hot observability flow; [devices] and [devicesFlow] are the topology source of truth. */
    public val hubEvents: Flow<HubEvent>

}

/** Thrown by [DeviceHub.attach] when the target name is already taken. */
@Suppress("CanBeParameter")
public class HubConflictException(
    public val conflictName: Name,
    public val reason: String,
) : Exception("Hub attach conflict for '$conflictName': $reason")
