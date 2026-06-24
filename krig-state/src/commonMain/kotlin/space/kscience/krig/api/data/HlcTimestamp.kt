package space.kscience.krig.api.data

import kotlinx.serialization.Serializable
import space.kscience.dataforge.names.Name

/**
 * Hybrid logical timestamp: physical milliseconds + logical counter + originating node.
 *
 * For distributed causal ordering; compare only with other [HlcTimestamp]s, not with wall-clock
 * instants. [nodeId] is the final, deterministic total-order tie-breaker when two timestamps share
 * the same physical and logical components but originate from different nodes; the default
 * [Name.EMPTY] means "node unspecified" (single-source logs keep their previous ordering).
 *
 * [logicalCounter] is a [Long]: a backward wall-clock correction makes the counter advance once per
 * tick until physical time catches up, so a 32-bit counter could in principle overflow under a large
 * regression at high tick rates. A 64-bit counter removes that failure mode entirely.
 */
@Serializable
public data class HlcTimestamp(
    val physicalMilliseconds: Long,
    val logicalCounter: Long,
    val nodeId: Name = Name.EMPTY,
) : Comparable<HlcTimestamp> {
    override fun compareTo(other: HlcTimestamp): Int {
        val byPhysical = physicalMilliseconds.compareTo(other.physicalMilliseconds)
        if (byPhysical != 0) return byPhysical
        val byLogical = logicalCounter.compareTo(other.logicalCounter)
        if (byLogical != 0) return byLogical
        return compareNames(nodeId, other.nodeId)
    }
}

/**
 * Deterministic, allocation-free total order over [Name]s: lexicographic over name-token body then
 * index, then by token count. Avoids [Name.toString] (which builds a joined string); used as the HLC
 * node tie-breaker on the ordering path.
 */
public fun compareNames(left: Name, right: Name): Int {
    val leftTokens = left.tokens
    val rightTokens = right.tokens
    val shared = minOf(leftTokens.size, rightTokens.size)
    for (i in 0 until shared) {
        val byBody = leftTokens[i].body.compareTo(rightTokens[i].body)
        if (byBody != 0) return byBody
        val byIndex = compareValues(leftTokens[i].index, rightTokens[i].index)
        if (byIndex != 0) return byIndex
    }
    return leftTokens.size.compareTo(rightTokens.size)
}
