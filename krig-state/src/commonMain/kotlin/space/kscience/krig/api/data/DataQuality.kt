package space.kscience.krig.api.data

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/** Open severity scale for data-quality reduction. Higher rank is worse. */
@Serializable
@JvmInline
public value class QualitySeverity(public val rank: Int) : Comparable<QualitySeverity> {
    override fun compareTo(other: QualitySeverity): Int = rank.compareTo(other.rank)

    /** Stable short label for logs, demos, notebooks, and compact UI badges. */
    public val label: String get() = when (this) {
        GOOD -> "GOOD"
        UNCERTAIN -> "UNCERTAIN"
        BAD -> "BAD"
        else -> "severity($rank)"
    }

    public companion object {
        public val GOOD: QualitySeverity = QualitySeverity(0)
        public val UNCERTAIN: QualitySeverity = QualitySeverity(50)
        public val BAD: QualitySeverity = QualitySeverity(100)
    }
}

/** Stable namespaced quality code, for example `opcua.stale` or `modbus.timeout`. */
@Serializable
@JvmInline
public value class QualityCode(public val id: String) {
    init {
        require(id.isNotBlank()) { "Quality code must not be blank" }
    }
}

/** Flat data-quality DTO for observed values. */
@Serializable
public data class DataQuality(
    public val severity: QualitySeverity,
    public val code: QualityCode? = null,
    public val detail: String? = null,
) : Comparable<DataQuality> {
    /**
     * Total order used by [combine]. The primary key is [severity] (worse is greater);
     * ties are broken deterministically by [code] then [detail], where an absent value
     * sorts below a present one and present values compare lexicographically. The order
     * is consistent with [equals], so [compareTo] returns `0` only for equal qualities.
     */
    override fun compareTo(other: DataQuality): Int {
        val bySeverity = severity.compareTo(other.severity)
        if (bySeverity != 0) return bySeverity
        val byCode = compareValues(code?.id, other.code?.id)
        if (byCode != 0) return byCode
        return compareValues(detail, other.detail)
    }

    /**
     * Worst-wins join: the least upper bound of the two qualities under [compareTo].
     *
     * Forms a bounded join-semilattice. The operation is idempotent, commutative and
     * associative, and [GOOD] is the identity (bottom) element. Reducing a collection of
     * qualities therefore yields the same result regardless of order (see [combineAll]).
     * The deterministic tie-break keeps a single representative when severities are equal;
     * use a domain-specific policy when multi-cause provenance must be preserved.
     */
    public fun combine(other: DataQuality): DataQuality = maxOf(this, other)

    /** Compact human-facing label: severity plus optional stable quality code. */
    public val shortLabel: String
        get() = code?.let { "${severity.label}:${it.id}" } ?: severity.label

    public companion object {
        public val GOOD: DataQuality = DataQuality(QualitySeverity.GOOD)
    }
}

/** Worst-wins reduction over a collection of qualities: the supremum under the [DataQuality] order. */
public fun Iterable<DataQuality>.combineAll(): DataQuality {
    var result = DataQuality.GOOD
    for (q in this) result = result.combine(q)
    return result
}
