package space.kscience.krig.api.data

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/** Open severity scale for data-quality reduction. Higher rank is worse. */
@Serializable
@JvmInline
public value class QualitySeverity(public val rank: Int) : Comparable<QualitySeverity> {
    override fun compareTo(other: QualitySeverity): Int = rank.compareTo(other.rank)

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
) {
    /** Worst-wins merge. Details from the losing side are discarded. */
    public fun combine(other: DataQuality): DataQuality =
        if (severity >= other.severity) this else other

    public companion object {
        public val GOOD: DataQuality = DataQuality(QualitySeverity.GOOD)
    }
}

/** Worst-wins reduction over a collection of qualities. */
public fun Iterable<DataQuality>.combineAll(): DataQuality {
    var result = DataQuality.GOOD
    for (q in this) result = result.combine(q)
    return result
}
