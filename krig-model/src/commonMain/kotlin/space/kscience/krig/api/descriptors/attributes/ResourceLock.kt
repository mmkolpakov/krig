package space.kscience.krig.api.descriptors.attributes

import kotlinx.serialization.Serializable
import space.kscience.dataforge.names.Name

/**
 * Declarative exclusive lock hint for an operation that touches a shared resource.
 *
 * Resource locks serialize access to hardware-facing or otherwise non-reentrant resources.
 * Parallel reads should use lock-free state/sampling paths and leave
 * [BehaviorAttribute.requiredLocks] empty.
 */
@Serializable
public data class ResourceLock(
    public val resourceName: Name,
)
