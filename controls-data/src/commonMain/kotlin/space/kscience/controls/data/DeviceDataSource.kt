package space.kscience.controls.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import space.kscience.controls.core.legacy_alpha_2.contracts.Device
import space.kscience.controls.core.InternalControlsApi
import space.kscience.controls.api.context.ExecutionContext
import space.kscience.controls.api.context.SystemPrincipal
import space.kscience.controls.api.messages.PropertyChangedMessage
import space.kscience.controls.api.descriptors.PropertyDescriptor
import space.kscience.dataforge.data.Data
import space.kscience.dataforge.data.DataTree
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.misc.UnsafeKType
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.NameToken
import space.kscience.dataforge.names.asName
import space.kscience.dataforge.names.first
import space.kscience.dataforge.names.isEmpty
import space.kscience.dataforge.names.plus
import space.kscience.dataforge.names.removeFirstOrNull
import space.kscience.dataforge.names.startsWith
import kotlin.reflect.KType
import kotlin.reflect.typeOf

/**
 * A private, unified implementation for all nodes within a Device-backed DataTree.
 * This class correctly implements the `DataTree` contract to support both hierarchical
 * traversal and direct access by full name.
 *
 * @param rootDevice The root device instance, used for all property reads and message flow subscription.
 * @param basePath The hierarchical path from the absolute root of the tree to this specific node.
 * @param allDescriptors The complete collection of all property descriptors from the root device. This collection
 *                       is passed down recursively to all child nodes.
 */
private class DeviceDataNode(
    private val rootDevice: Device,
    private val basePath: Name,
    private val allDescriptors: Collection<PropertyDescriptor>,
) : DataTree<Meta> {

    override val dataType: KType = typeOf<Meta>()

    /**
     * The data associated with this specific node, if it represents a terminal property.
     * This is determined by finding a descriptor whose name exactly matches this node's full path.
     * The data fetching itself is lazy and happens only upon request.
     */
    @OptIn(UnsafeKType::class, InternalControlsApi::class)
    override val data: Data<Meta>? by lazy {
        allDescriptors.find { it.name == basePath }?.let { descriptor ->
            Data(typeOf<Meta>()) {
                rootDevice.readProperty(descriptor.name, ExecutionContext(SystemPrincipal))
            }
        }
    }

    /**
     * A map of direct child nodes. This is the core of the hierarchical structure.
     * It is constructed by finding all descendant properties and grouping them by the
     * next token in their name relative to this node's path.
     */
    override val items: Map<NameToken, DataTree<Meta>> by lazy {
        allDescriptors
            .mapNotNull { descriptor ->
                val tail = descriptor.name.removeFirstOrNull(basePath)
                if (tail != null && !tail.isEmpty()) {
                    tail.first()
                } else {
                    null
                }
            }
            .toSet()
            .associateWith { token ->
                DeviceDataNode(rootDevice, basePath + token, allDescriptors)
            }
    }

    /**
     * A flow of update notifications for properties within this branch of the tree.
     * It filters the root device's message flow based on the `basePath` of this node.
     */
    override val updates: Flow<Name> by lazy {
        rootDevice.messageFlow
            .filterIsInstance<PropertyChangedMessage>()
            .map { it.property.asName() }
            .filter { it.startsWith(basePath) }
            .mapNotNull { it.removeFirstOrNull(basePath) }
    }
}

/**
 * Exposes a [Device] as a [DataTree<Meta>], allowing its properties to be consumed
 * as lazy, asynchronous data points. This is the primary mechanism for integrating
 * `controls-composite` devices with `dataforge-workspace`.
 *
 * The resulting [DataTree] supports both direct property access by full name
 * (e.g., `tree["a.b.c"]`) and hierarchical traversal (e.g., `tree.branch("a")?.items`)
 * by correctly implementing the `DataTree` contract.
 *
 * @return A [DataTree] view over the device's properties.
 */
public fun Device.asDataTree(): DataTree<Meta> = DeviceDataNode(
    rootDevice = this,
    basePath = Name.EMPTY,
    allDescriptors = this.propertyDescriptors
)