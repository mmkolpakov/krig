package space.kscience.controls.data

import kotlinx.coroutines.flow.Flow
import space.kscience.dataforge.data.DataTree
import space.kscience.dataforge.names.Name

/**
 * A [DataTree] that can notify observers about changes to its structure,
 * such as the addition or removal of devices. This is crucial for UIs and other services
 * that need to react dynamically to the topology of a device hub.
 *
 * @param T The type of data contained in the tree.
 */
public interface ObservableDataTree<T> : DataTree<T> {
    /**
     * A hot [Flow] that emits [StructureChangeEvent]s when the structure of the tree changes.
     */
    public val structureUpdates: Flow<StructureChangeEvent>
}