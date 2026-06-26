package space.kscience.krig.api.data

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/**
 * Stable node identifier used by [HlcTimestamp] for deterministic distributed ordering.
 *
 * The empty value means "node unspecified" and preserves single-source ordering. It is deliberately
 * a KRig-local string wrapper rather than a DataForge `Name`, so the low-level state artifact stays
 * free of DataForge dependencies.
 */
@Serializable
@JvmInline
public value class HlcNodeId(public val value: String) : Comparable<HlcNodeId> {
    override fun compareTo(other: HlcNodeId): Int = value.compareTo(other.value)

    public fun isUnspecified(): Boolean = value.isEmpty()

    override fun toString(): String = value

    public companion object {
        public val Unspecified: HlcNodeId = HlcNodeId("")
    }
}

/**
 * Hybrid logical timestamp: physical milliseconds + logical counter + originating node.
 *
 * For distributed causal ordering; compare only with other [HlcTimestamp]s, not with wall-clock
 * instants. [nodeId] is the final, deterministic total-order tie-breaker when two timestamps share
 * the same physical and logical components but originate from different nodes; the default
 * [HlcNodeId.Unspecified] means "node unspecified" (single-source logs keep their previous ordering).
 *
 * [logicalCounter] is a [Long]: a backward wall-clock correction makes the counter advance once per
 * tick until physical time catches up, so a 32-bit counter could in principle overflow under a large
 * regression at high tick rates. A 64-bit counter removes that failure mode entirely.
 */
@Serializable
public data class HlcTimestamp(
    val physicalMilliseconds: Long,
    val logicalCounter: Long,
    val nodeId: HlcNodeId = HlcNodeId.Unspecified,
) : Comparable<HlcTimestamp> {
    override fun compareTo(other: HlcTimestamp): Int {
        val byPhysical = physicalMilliseconds.compareTo(other.physicalMilliseconds)
        if (byPhysical != 0) return byPhysical
        val byLogical = logicalCounter.compareTo(other.logicalCounter)
        if (byLogical != 0) return byLogical
        return nodeId.compareTo(other.nodeId)
    }
}
