package space.kscience.krig.storage.quality

import space.kscience.kmath.structures.Buffer
import space.kscience.kmath.structures.FlaggedBuffer
import space.kscience.kmath.structures.ValueFlag
import space.kscience.kmath.structures.isValid
import space.kscience.krig.api.data.QualitySeverity

/**
 * Bridges a KRig severity band (a parallel rank column — e.g. `AbstractRingSampler.snapshotSeverityRanks`
 * or a columnar time-series quality lane) onto KMath's [FlaggedBuffer], so KMath algebras can consume
 * KRig telemetry and skip untrustworthy points via `isValid`/`forEachValid`.
 *
 * The two quality models are deliberately **not** interchangeable, which is why this adapter exists
 * instead of feeding a raw `ByteArray` of ranks into [space.kscience.kmath.structures.FlaggedDoubleBuffer]:
 * - KRig [QualitySeverity] is an **ordered rank** (GOOD=0 < UNCERTAIN=50 < BAD=100), worst-wins.
 * - KMath [ValueFlag] is a **bitmask** (NaN / Missing / ±Inf), OR-combined.
 *
 * Translation happens lazily in [getFlag]: a rank at or above [invalidAtOrAbove] becomes [ValueFlag.MISSING]
 * (the value is treated as absent by KMath), and a NaN value sets [ValueFlag.NAN]. The original ordered
 * rank is preserved and reachable through [severityAt] — the flag projection never overwrites it.
 */
public class KrigFlaggedDoubleBuffer(
    public val values: DoubleArray,
    public val severityRanks: IntArray,
    private val invalidAtOrAbove: QualitySeverity = QualitySeverity.BAD,
) : FlaggedBuffer<Double?>, Buffer<Double?> {

    init {
        require(values.size == severityRanks.size) {
            "values and severityRanks must have the same size: ${values.size} vs ${severityRanks.size}"
        }
    }

    override val size: Int get() = values.size

    override fun getFlag(index: Int): Byte {
        var flag = 0
        if (values[index].isNaN()) flag = flag or ValueFlag.NAN.mask.toInt()
        if (severityRanks[index] >= invalidAtOrAbove.rank) flag = flag or ValueFlag.MISSING.mask.toInt()
        return flag.toByte()
    }

    override operator fun get(index: Int): Double? = if (isValid(index)) values[index] else null

    override operator fun iterator(): Iterator<Double?> = values.indices.asSequence().map {
        if (isValid(it)) values[it] else null
    }.iterator()

    /** The original ordered KRig severity at [index] (not collapsed to a bitmask flag). */
    public fun severityAt(index: Int): QualitySeverity = QualitySeverity(severityRanks[index])

    override fun toString(): String = Buffer.toString(this)
}
