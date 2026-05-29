package space.kscience.krig.api.hub

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import space.kscience.krig.api.messages.DeviceDepartureReason
import space.kscience.krig.core.UnstableKrigForSubclassing
import space.kscience.krig.core.contracts.Device
import space.kscience.krig.core.contracts.DeviceNode
import space.kscience.dataforge.names.Name

/**
 * Mutation API for owned device topology.
 */
@SubclassOptInRequired(UnstableKrigForSubclassing::class)
public interface DeviceHub : Device, DeviceNode {

    override fun content(target: String): Map<Name, Any> =
        if (target == defaultTarget) children else emptyMap()

    /** Direct child devices owned by this hub. */
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
     * Detach the device under [name]. Cascades `shutdown()`. Emits [HubEvent.Detached] with
     * [reason]. Returns the detached device or `null` if absent (idempotent).
     */
    public suspend fun detach(
        name: Name,
        reason: DeviceDepartureReason = DeviceDepartureReason.Graceful,
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
