package space.kscience.krig.assembly

import space.kscience.kmath.UnstableKMathAPI
import space.kscience.kmath.data.XYColumnarData
import space.kscience.kmath.interpolation.linearInterpolator
import space.kscience.kmath.interpolation.splineInterpolator
import space.kscience.kmath.operations.Float64Field
import space.kscience.kmath.structures.Float64Buffer
import space.kscience.krig.storage.timeseries.DenseDoubleTimeSeriesChunk
import space.kscience.krig.storage.timeseries.DenseDoubleTimeSeriesRow
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.Instant

/** Interpolation kernel used by [resampleOnto]. */
public enum class ResamplingMethod { LINEAR, SPLINE }

/**
 * Resamples every series onto [grid] using KMath interpolation. Grid points outside the observed
 * time span are dropped — values are never extrapolated. Each output row inherits the combined
 * quality of the two source samples bracketing it. [ResamplingMethod.SPLINE] requires at least
 * three source rows; [ResamplingMethod.LINEAR] requires at least two.
 */
@OptIn(UnstableKMathAPI::class)
public fun DenseDoubleTimeSeriesChunk.resampleOnto(
    grid: List<Instant>,
    method: ResamplingMethod = ResamplingMethod.LINEAR,
): DenseDoubleTimeSeriesChunk {
    val minRows = if (method == ResamplingMethod.SPLINE) 3 else 2
    require(rowCount >= minRows) {
        "$method resampling needs at least $minRows source rows, got $rowCount."
    }
    val origin = times.first()
    fun seconds(instant: Instant): Double = (instant - origin).toDouble(DurationUnit.SECONDS)

    val xs = Float64Buffer(rowCount) { seconds(times[it]) }
    val lastX = xs[rowCount - 1]
    val targets = grid.asSequence()
        .map { it to seconds(it) }
        .filter { (_, x) -> x in 0.0..lastX }
        .toList()
    if (targets.isEmpty()) return DenseDoubleTimeSeriesChunk(series, emptyList())

    val evaluators = series.indices.map { seriesIndex ->
        val data = XYColumnarData.of(xs, column(seriesIndex))
        when (method) {
            ResamplingMethod.LINEAR -> Float64Field.linearInterpolator.interpolate(data)
            ResamplingMethod.SPLINE -> Float64Field.splineInterpolator.interpolate(data)
        }
    }

    val lastRow = rowCount - 1
    val rows = targets.map { (instant, x) ->
        // Piecewise polynomials are right-open: the exact right endpoint falls outside, so take the
        // known last sample there instead of evaluating the interpolator.
        val values = DoubleArray(series.size) { if (x >= lastX) value(lastRow, it) else evaluators[it](x) }
        val lo = bracketIndex(xs, x)
        val quality = aggregateQualityAt(lo).combine(aggregateQualityAt(lo + 1))
        DenseDoubleTimeSeriesRow(instant, values, baselineQuality = quality)
    }
    return DenseDoubleTimeSeriesChunk(series, rows)
}

/**
 * Resamples onto a uniform grid of [step] spanning the observed time range (first sample inclusive,
 * last sample inclusive). Convenience over [resampleOnto] for evenly spaced output.
 */
public fun DenseDoubleTimeSeriesChunk.resample(
    step: Duration,
    method: ResamplingMethod = ResamplingMethod.LINEAR,
): DenseDoubleTimeSeriesChunk {
    require(step.isPositive()) { "resampling step must be positive, got $step." }
    require(rowCount > 0) { "Cannot resample an empty chunk." }
    val start = times.first()
    val end = times.last()
    val grid = buildList {
        var t = start
        while (t <= end) {
            add(t)
            t += step
        }
    }
    return resampleOnto(grid, method)
}

/** Largest index `lo` with `xs[lo] <= x`, clamped so `lo + 1` stays in range. */
private fun bracketIndex(xs: Float64Buffer, x: Double): Int {
    var low = 0
    var high = xs.size - 1
    while (low < high) {
        val mid = (low + high + 1) / 2
        if (xs[mid] <= x) low = mid else high = mid - 1
    }
    return low.coerceAtMost(xs.size - 2)
}
