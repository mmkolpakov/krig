@file:Suppress("MagicNumber")

package space.kscience.krig.benchmarks.storage

import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import space.kscience.krig.benchmarks.bootstrapMedianCi
import space.kscience.krig.benchmarks.cliffsDelta
import space.kscience.krig.benchmarks.cliffsMagnitude
import space.kscience.krig.benchmarks.mannWhitneyU
import space.kscience.krig.benchmarks.meanValue
import space.kscience.krig.benchmarks.median
import space.kscience.krig.benchmarks.percentile
import space.kscience.krig.benchmarks.sampleStdDev

/**
 * Repeated-run companion for [MacroStorageBench]. Executes the in-process H2 and columnar-chunk
 * storage scenarios (no Docker, no TimescaleDB) [repeats] times and reports each scenario's write and
 * read time as a mean with its sample standard deviation (StdDev) — the statistical rigor a Q1 study
 * expects in place of a single-run figure. Stored size (bytes/value) is deterministic for a fixed
 * workload, so only its single value is reported.
 *
 * The recorded controls-kt comparison row is an external reference run on another branch and is not
 * re-measured here; it is kept as a single recorded reference figure.
 *
 * Run with: `./gradlew :krig-benchmarks:storageStats` (override count via KRIG_STORAGE_BENCH_REPEATS).
 */
private const val DEFAULT_REPEATS = 30

private fun StorageBenchResults.all(): List<BenchResult> = exposed + jdbc + dense + architecture

private fun List<Double>.meanSd(): Pair<Double, Double> = meanValue() to sampleStdDev()

private fun fmtMs(mean: Double, sd: Double): String =
    if (mean >= 1000.0) {
        "%.3f ± %.3f s".format(reportLocale, mean / 1000.0, sd / 1000.0)
    } else {
        "%.1f ± %.1f ms".format(reportLocale, mean, sd)
    }

private fun fmtMs1(value: Double): String =
    if (value >= 1000.0) "%.3f s".format(reportLocale, value / 1000.0) else "%.1f ms".format(reportLocale, value)

private fun fmtMsCi(median: Double, lo: Double, hi: Double): String =
    "${fmtMs1(median)} [${fmtMs1(lo)}; ${fmtMs1(hi)}]"

private fun StringBuilder.appendEffectSize(exposed: List<Double>?, jdbc: List<Double>?) {
    if (exposed == null || jdbc == null) return
    val delta = cliffsDelta(exposed, jdbc)
    val mw = mannWhitneyU(exposed, jdbc)
    appendLine("## Effect size: Exposed ORM vs direct JDBC (event-json write time)")
    appendLine()
    appendLine("Both paths re-measured each repeat on the same workload and machine.")
    appendLine()
    appendLine(
        "- Cliff's delta (Exposed - JDBC) = ${"%.3f".format(reportLocale, delta)} " +
            "(${cliffsMagnitude(delta)} эффект)",
    )
    appendLine(
        "- Mann-Whitney U = ${"%.1f".format(reportLocale, mw.u)}, z = ${"%.3f".format(reportLocale, mw.z)}, " +
            "two-sided p = ${"%.5f".format(reportLocale, mw.pTwoSided)}",
    )
    appendLine()
}

fun main() {
    val repeats = (envInt("KRIG_STORAGE_BENCH_REPEATS") ?: DEFAULT_REPEATS).coerceAtLeast(2)
    val config = BenchConfig()
    config.root.createDirectories()

    val referenceRows = MatrixWorkload.randomWalk(
        id = "reference-rows",
        tags = config.referenceRowsTags,
        rows = config.referenceRowsRows,
        seed = 0,
        stepFrom = -0.1,
        stepUntil = 0.1,
    )
    val matrix = MatrixWorkload.deterministic("matrix", config.matrixTags, config.matrixRows)

    val writeSamples = LinkedHashMap<String, MutableList<Double>>()
    val readSamples = LinkedHashMap<String, MutableList<Double>>()
    val values = LinkedHashMap<String, Int>()
    val bytesPerValue = LinkedHashMap<String, Double>()

    val shape = "events=${config.controlsEvents}, " +
        "refRows=${referenceRows.tags}x${referenceRows.rows}, matrix=${matrix.tags}x${matrix.rows}"
    println("krig storage stats bench: $repeats repeats")
    println(shape)

    repeat(repeats) { iteration ->
        val run = storageBench(config) {
            h2ExposedJournal()
            h2JdbcJournal()
            compatibleRows(referenceRows)
            h2Matrix(matrix)
        }.all()
        run.forEach { r ->
            writeSamples.getOrPut(r.scenario) { mutableListOf() } += r.write.inWholeNanoseconds / 1_000_000.0
            readSamples.getOrPut(r.scenario) { mutableListOf() } += r.read.inWholeNanoseconds / 1_000_000.0
            values[r.scenario] = r.values
            r.bytes?.let { bytesPerValue[r.scenario] = it.toDouble() / r.values.coerceAtLeast(1) }
        }
        println("  repeat ${iteration + 1}/$repeats done")
    }

    val env = "${System.getProperty("java.vm.name")} ${System.getProperty("java.version")}"
    val report = buildString {
        appendLine("# krig storage stats bench (repeated runs)")
        appendLine()
        appendLine("JVM: $env; GC: default (G1).")
        appendLine(
            "Repeats: $repeats. Descriptive statistics via kmath-stat (Mean/StandardDeviation/Quantile). " +
                "Time metrics are distributional; bytes/value is deterministic.",
        )
        appendLine(shape)
        appendLine()
        appendLine("## Central tendency and dispersion (write/read time)")
        appendLine()
        appendLine(
            "| scenario | values | bytes/value | write mean ± StdDev | write median [95% CI] | " +
                "write p95 / p99 | read median [95% CI] |",
        )
        appendLine("|---|---:|---:|---:|---:|---:|---:|")
        writeSamples.keys.forEach { scenario ->
            val w = writeSamples.getValue(scenario)
            val r = readSamples.getValue(scenario)
            val (wMean, wSd) = w.meanSd()
            val (wLo, wHi) = w.bootstrapMedianCi()
            val (rLo, rHi) = r.bootstrapMedianCi()
            val bpv = bytesPerValue[scenario]?.let { "%.2f".format(reportLocale, it) } ?: "—"
            appendLine(
                "| $scenario | ${values[scenario]} | $bpv | ${fmtMs(wMean, wSd)} | " +
                    "${fmtMsCi(w.median(), wLo, wHi)} | ${fmtMs1(w.percentile(0.95))} / ${fmtMs1(w.percentile(0.99))} | " +
                    "${fmtMsCi(r.median(), rLo, rHi)} |",
            )
        }
        appendLine()
        appendEffectSize(writeSamples["krig.exposed.h2.event-json"], writeSamples["krig.jdbc.h2.event-json"])
    }
    print(report)
    val out = config.root.resolve("storage-stats-results.md")
    out.writeText(report)
    println("Report: ${out.absolutePathString()}")
}
