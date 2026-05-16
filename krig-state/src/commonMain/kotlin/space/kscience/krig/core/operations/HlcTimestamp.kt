package space.kscience.krig.core.operations

import kotlinx.serialization.Serializable

/**
 * Hybrid logical timestamp: physical milliseconds + logical counter. For distributed
 * causal ordering; compare only with other [HlcTimestamp]s, not with wall-clock instants.
 */
@Serializable
public data class HlcTimestamp(
    val physicalMilliseconds: Long,
    val logicalCounter: Int,
) : Comparable<HlcTimestamp> {
    override fun compareTo(other: HlcTimestamp): Int =
        compareValuesBy(this, other, HlcTimestamp::physicalMilliseconds, HlcTimestamp::logicalCounter)
}
