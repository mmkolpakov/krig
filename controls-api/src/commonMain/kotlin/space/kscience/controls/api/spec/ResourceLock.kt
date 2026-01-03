package space.kscience.controls.api.spec

import kotlinx.serialization.Serializable
import space.kscience.dataforge.names.Name

/**
 * Defines the type of lock to be acquired on a shared resource.
 */
@Serializable
public enum class LockType {
    /**
     * A shared (read) lock.
     */
    SHARED_READ,

    /**
     * An exclusive (write) lock.
     */
    EXCLUSIVE_WRITE
}

/**
 * A serializable, declarative specification for a lock required by a device property or action.
 *
 * @property resourceName The unique, hierarchical name of the resource to be locked.
 * @property lockType The type of lock required (`SHARED_READ` or `EXCLUSIVE_WRITE`).
 */
@Serializable
public data class ResourceLockSpec(
    public val resourceName: Name,
    public val lockType: LockType,
)