package space.kscience.krig.assembly

import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName
import space.kscience.dataforge.names.plus
import space.kscience.kmath.structures.Float64Buffer
import space.kscience.krig.api.data.DataQuality
import space.kscience.krig.storage.timeseries.DenseDoubleTimeSeriesChunk
import space.kscience.krig.storage.timeseries.DenseDoubleTimeSeriesRow
import space.kscience.krig.storage.timeseries.lastOrNaN
import space.kscience.krig.storage.timeseries.maxOrNaN
import space.kscience.krig.storage.timeseries.mean
import space.kscience.krig.storage.timeseries.minOrNaN
import kotlin.time.Duration
import kotlin.time.DurationUnit

/** Column suffixes one source series expands into; empty name means the series keeps its identity. */
public val ReductionSpec.outputSuffixes: List<Name>
    get() = when (this) {
        ReductionSpec.MinMaxMean -> listOf("min".asName(), "max".asName(), "mean".asName())
        else -> listOf(Name.EMPTY)
    }

/**
 * Bin-reduction algebra over an unboxed [Float64Buffer], the extension point behind a
 * [ReductionSpec.Named] (`p95`, `rms`, weighted means). [Float64Buffer] is a KMath `Buffer<Double>`,
 * so a future `kmath-stat` `Statistic<Double, Double>` adapts directly:
 * `Reducer { Float64Buffer(doubleArrayOf(stat.evaluateBlocking(it))) }`. Built-in reductions stay
 * unboxed and do not go through this interface.
 */
public fun interface Reducer {
    public fun reduce(samples: Float64Buffer): Float64Buffer
}

/**
 * Reduces one series' bin [samples] to its output values. Built-in specs use unboxed buffer
 * reductions; [ReductionSpec.Named] is resolved against [reducers].
 */
public fun ReductionSpec.reduce(
    samples: Float64Buffer,
    reducers: Map<Name, Reducer> = emptyMap(),
): Float64Buffer = when (this) {
    ReductionSpec.Last -> Float64Buffer(doubleArrayOf(samples.lastOrNaN()))
    ReductionSpec.Mean -> Float64Buffer(doubleArrayOf(samples.mean()))
    ReductionSpec.MinMaxMean -> Float64Buffer(doubleArrayOf(samples.minOrNaN(), samples.maxOrNaN(), samples.mean()))
    is ReductionSpec.Named ->
        (reducers[name] ?: error("No reducer registered for named reduction '$name'.")).reduce(samples)
}

/**
 * Downsamples consecutive, time-ordered rows into fixed-width [bin]s, collapsing each series with
 * [reduction]. Each bin yields one row stamped at its first sample; bin quality is the combination
 * of the source rows' aggregate quality. [ReductionSpec.MinMaxMean] expands every series into
 * `min`/`max`/`mean` columns.
 */
public fun DenseDoubleTimeSeriesChunk.reduceToBins(
    bin: Duration,
    reduction: ReductionSpec,
    reducers: Map<Name, Reducer> = emptyMap(),
): DenseDoubleTimeSeriesChunk {
    require(bin.isPositive()) { "bin duration must be positive, got $bin." }
    val outputSeries = series.flatMap { name ->
        reduction.outputSuffixes.map { suffix -> if (suffix == Name.EMPTY) name else name + suffix }
    }
    if (rowCount == 0) return DenseDoubleTimeSeriesChunk(outputSeries, emptyList())

    val binNanos = bin.inWholeNanoseconds
    val origin = times.first()
    val reducedRows = ArrayList<DenseDoubleTimeSeriesRow>()
    var start = 0
    while (start < rowCount) {
        val binIndex = (times[start] - origin).inWholeNanoseconds / binNanos
        var end = start
        while (end < rowCount && (times[end] - origin).inWholeNanoseconds / binNanos == binIndex) end++

        val values = DoubleArray(outputSeries.size)
        var column = 0
        for (seriesIndex in series.indices) {
            val samples = Float64Buffer(end - start) { value(start + it, seriesIndex) }
            val reduced = reduction.reduce(samples, reducers)
            for (k in 0 until reduced.size) values[column++] = reduced[k]
        }
        var quality = DataQuality.GOOD
        for (r in start until end) quality = quality.combine(aggregateQualityAt(r))
        reducedRows += DenseDoubleTimeSeriesRow(times[start], values, baselineQuality = quality)
        start = end
    }
    return DenseDoubleTimeSeriesChunk(outputSeries, reducedRows)
}

/**
 * Trapezoidal time-integral of series [seriesIndex] (rate → accumulated total). The result is in
 * *value units × seconds*; segments touching a non-finite sample are skipped so gaps don't poison
 * the total. Exact for piecewise-linear signals and deterministic — safe on the replay path.
 */
public fun DenseDoubleTimeSeriesChunk.totalize(seriesIndex: Int): Double {
    require(seriesIndex in series.indices) {
        "Series index must be inside 0 until ${series.size}, got $seriesIndex."
    }
    if (rowCount < 2) return 0.0
    var total = 0.0
    var previous = value(0, seriesIndex)
    for (i in 1 until rowCount) {
        val current = value(i, seriesIndex)
        if (previous.isFinite() && current.isFinite()) {
            total += 0.5 * (previous + current) * (times[i] - times[i - 1]).toDouble(DurationUnit.SECONDS)
        }
        previous = current
    }
    return total
}

/** [totalize] by series [name]; throws when [name] is not a column of this chunk. */
public fun DenseDoubleTimeSeriesChunk.totalize(name: Name): Double {
    val index = series.indexOf(name)
    require(index >= 0) { "Series '$name' is not present in this chunk." }
    return totalize(index)
}
