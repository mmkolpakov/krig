package space.kscience.krig.core.contracts

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.provider.Provider

/** Read-only topology node. A folder node may have no [device]. */
public interface DeviceNode : Provider {
    public val device: Device?

    public val children: Map<Name, DeviceNode>

    public val childrenFlow: StateFlow<Map<Name, DeviceNode>>
        get() = EmptyDeviceNodeChildren

    override fun content(target: String): Map<Name, Any> =
        if (target == defaultTarget) children else emptyMap()
}

/** Immutable topology node. */
public fun DeviceNode(
    device: Device? = null,
    children: Map<Name, DeviceNode> = emptyMap(),
): DeviceNode = object : DeviceNode {
    override val device: Device? = device
    override val children: Map<Name, DeviceNode> = children
}

/** Factory for code that reads better in tree assembly contexts. */
public fun deviceTree(
    root: Device? = null,
    children: Map<Name, DeviceNode> = emptyMap(),
): DeviceNode = DeviceNode(root, children)

/** Uses an existing topology view when [this] already exposes one. */
public fun Device.asNode(): DeviceNode = this as? DeviceNode ?: DeviceNode(device = this)

public fun Map<Name, Device>.asNodeMap(): Map<Name, DeviceNode> =
    mapValues { (_, device) -> device.asNode() }

/** Shared empty topology snapshot for leaf nodes. */
public val EmptyDeviceNodeChildren: StateFlow<Map<Name, DeviceNode>> =
    MutableStateFlow(emptyMap<Name, DeviceNode>()).asStateFlow()
