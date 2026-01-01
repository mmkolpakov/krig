package space.kscience.controls.data

import space.kscience.dataforge.data.DataTree
import space.kscience.dataforge.names.Name

/**
 * Represents a structural change in a [DataTree].
 * This is used by [ObservableDataTree] to notify observers about dynamic changes
 * in the device hierarchy, such as attaching or detaching devices.
 */
public sealed interface StructureChangeEvent {
    public val name: Name

    /**
     * An event indicating that a new item (a node or a leaf) has been attached to the tree.
     * @property name The full hierarchical name of the newly attached item.
     */
    public data class ItemAttached(override val name: Name) : StructureChangeEvent

    /**
     * An event indicating that an item has been detached from the tree.
     * @property name The full hierarchical name of the detached item.
     */
    public data class ItemDetached(override val name: Name) : StructureChangeEvent
}