package space.kscience.krig.api.spec

import kotlinx.serialization.Serializable
import space.kscience.dataforge.names.Name

/**
 * Declarative exclusive lock hint for a property or action that touches a shared resource.
 *
 * Resource locks serialize access to hardware-facing or otherwise non-reentrant resources.
 * Parallel reads should use lock-free state/sampling paths and leave [requiredLocks] empty.
 */
@Serializable
public data class ResourceLockSpec(
    public val resourceName: Name,
)
