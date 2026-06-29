@file:Suppress("MagicNumber")

package space.kscience.krig.benchmarks

import space.kscience.kmath.stat.Mean
import space.kscience.kmath.stat.Quantile
import space.kscience.kmath.stat.StandardDeviation
import space.kscience.kmath.structures.Buffer
import space.kscience.kmath.structures.Float64Buffer
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Non-parametric statistics for the benchmark companions.
 *
 * The descriptive layer — arithmetic mean, sample standard deviation and quantiles (median,
 * p95, p99) — is delegated to the sibling `space.kscience:kmath-stat` library rather than
 * re-implemented (`Mean`, `StandardDeviation`, `Quantile`; Hyndman–Fan definition 7, the R/NumPy
 * default). Only the parts outside kmath-stat's experimental scope are implemented directly: the
 * percentile-bootstrap confidence interval of the median, the tie-corrected Mann–Whitney U test
 * (kmath's [space.kscience.kmath.stat.Rank] truncates tied ranks) and the Cliff's δ effect size.
 *
 * Deterministic metrics (byte volume, allocation) are exact and do NOT use these helpers.
 */

@Volatile
private var probeSink: Any? = null

/** Keeps manual probe loops observable without writing debug text to benchmark reports. */
internal fun consumeProbeSink(value: Any?) {
    probeSink = value
}

private fun List<Double>.asF64Buffer(): Buffer<Double> = Float64Buffer(toDoubleArray())

/** Quantile at probability [p] in [0, 1] via kmath-stat (Hyndman–Fan def. 7). */
public fun List<Double>.percentile(p: Double): Double {
    require(isNotEmpty()) { "percentile of empty sample" }
    if (size == 1) return first()
    return Quantile.evaluate(p.coerceIn(0.0, 1.0), asF64Buffer())
}

public fun List<Double>.median(): Double = percentile(0.5)

public fun List<Double>.iqr(): Double = percentile(0.75) - percentile(0.25)

/** Arithmetic mean via kmath-stat [Mean]. */
public fun List<Double>.meanValue(): Double {
    require(isNotEmpty()) { "mean of empty sample" }
    return Mean.evaluate(asF64Buffer())
}

/** Bias-corrected (n − 1) sample standard deviation via kmath-stat [StandardDeviation]. */
public fun List<Double>.sampleStdDev(): Double =
    if (size < 2) 0.0 else StandardDeviation.evaluate(asF64Buffer())

/** Percentile-bootstrap CI of the median at the given [confidence]; returns (low, high). */
public fun List<Double>.bootstrapMedianCi(
    confidence: Double = 0.95,
    resamples: Int = 10_000,
    seed: Long = 0x5DEECE66DL,
): Pair<Double, Double> {
    if (size < 2) return first() to first()
    val rng = Random(seed)
    val medians = ArrayList<Double>(resamples)
    repeat(resamples) {
        val resample = List(size) { this[rng.nextInt(size)] }
        medians += resample.median()
    }
    val alpha = (1.0 - confidence) / 2.0
    return medians.percentile(alpha) to medians.percentile(1.0 - alpha)
}

/**
 * Cliff's δ effect size in [-1, 1]: the probability that a value of [a] exceeds a value of [b]
 * minus the reverse. δ > 0 means [a] tends to be larger.
 */
public fun cliffsDelta(a: List<Double>, b: List<Double>): Double {
    if (a.isEmpty() || b.isEmpty()) return 0.0
    var greater = 0L
    var less = 0L
    for (x in a) {
        for (y in b) {
            if (x > y) greater++ else if (x < y) less++
        }
    }
    return (greater - less).toDouble() / (a.size.toLong() * b.size.toLong()).toDouble()
}

/** Qualitative magnitude of Cliff's δ (Romano et al. thresholds). */
public fun cliffsMagnitude(delta: Double): String = when (val d = abs(delta)) {
    in 0.0..<0.147 -> "пренебрежимый"
    in 0.147..<0.330 -> "малый"
    in 0.330..<0.474 -> "средний"
    else -> if (d.isNaN()) "—" else "большой"
}

public data class MannWhitneyResult(val u: Double, val z: Double, val pTwoSided: Double)

/** Mann–Whitney U with average-rank tie correction and a normal approximation for two-sided p. */
public fun mannWhitneyU(a: List<Double>, b: List<Double>): MannWhitneyResult {
    val n1 = a.size
    val n2 = b.size
    if (n1 == 0 || n2 == 0) return MannWhitneyResult(Double.NaN, Double.NaN, Double.NaN)
    val combined = (a.map { it to 0 } + b.map { it to 1 }).sortedBy { it.first }

    val ranks = DoubleArray(combined.size)
    var i = 0
    while (i < combined.size) {
        var j = i
        while (j + 1 < combined.size && combined[j + 1].first == combined[i].first) j++
        val avgRank = (i + 1 + j + 1) / 2.0
        for (k in i..j) ranks[k] = avgRank
        i = j + 1
    }

    var rankSumA = 0.0
    combined.forEachIndexed { idx, (_, group) -> if (group == 0) rankSumA += ranks[idx] }
    val u1 = rankSumA - n1 * (n1 + 1) / 2.0
    val u2 = n1.toLong() * n2.toLong() - u1
    val u = minOf(u1, u2)

    val n = n1 + n2
    val tieCorrection = combined.groupBy { it.first }.values
        .sumOf { val t = it.size.toLong(); (t * t * t - t).toDouble() }
    val meanU = n1.toLong() * n2.toLong() / 2.0
    val varU = n1.toLong() * n2.toLong() / 12.0 *
        (n + 1 - tieCorrection / (n.toLong() * (n - 1)))
    if (varU <= 0.0) return MannWhitneyResult(u, Double.NaN, Double.NaN)
    val z = (u - meanU) / sqrt(varU)
    val p = 2.0 * (1.0 - standardNormalCdf(abs(z)))
    return MannWhitneyResult(u, z, p.coerceIn(0.0, 1.0))
}

/** Standard normal CDF via the Abramowitz–Stegun 7.1.26 error-function approximation. */
private fun standardNormalCdf(x: Double): Double {
    val t = 1.0 / (1.0 + 0.2316419 * abs(x))
    val d = 0.3989422804014327 * exp(-x * x / 2.0)
    val poly = t * (0.319381530 + t * (-0.356563782 + t * (1.781477937 + t * (-1.821255978 + t * 1.330274429))))
    val prob = d * poly
    return if (x >= 0.0) 1.0 - prob else prob
}
