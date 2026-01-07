package space.kscience.controls.core.legacy_alpha_2.contracts

import kotlinx.serialization.Serializable
import space.kscience.controls.api.identifiers.BlueprintId
import space.kscience.controls.api.lifecycle.DeviceLifecycleState
import space.kscience.dataforge.meta.Meta

/**
 * A serializable descriptor for a single device within a desired hub state.
 *
 * @property blueprintId The ID of the blueprint for this device.
 * @property meta The configuration meta to be applied to this device instance.
 * @property desiredState The desired lifecycle state for this device (`Running` or `Stopped`).
 */
@Serializable
public data class DeviceStateDescriptor(
    val blueprintId: BlueprintId,
    val meta: Meta = Meta.EMPTY,
    val desiredState: DeviceLifecycleState = DeviceLifecycleState.Running,
)