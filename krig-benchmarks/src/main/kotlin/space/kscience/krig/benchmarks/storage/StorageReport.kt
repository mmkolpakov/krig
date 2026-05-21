package space.kscience.krig.benchmarks.storage

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolutePathString

internal fun printReference() {
    println()
    println("controls-kt reference")
    println("| scenario | values | write | read | bytes | bytes/value | note |")
    println("|---|---:|---:|---:|---:|---:|---|")
    controlsReferences.forEach { println(it.markdownRow()) }
    println()
    println("controls-kt comparison run")
    println("| scenario | values | write | read | bytes | bytes/value | note |")
    println("|---|---:|---:|---:|---:|---:|---|")
    controlsLocalRerun.forEach { println(it.markdownRow()) }
}

internal fun printResults(title: String, results: List<BenchResult>) {
    println()
    println(title)
    println("| scenario | rows | values | write | read | bytes | bytes/value | note |")
    println("|---|---:|---:|---:|---:|---:|---:|---|")
    results.forEach { println(it.markdownRow()) }
}

internal fun writeReport(
    path: Path,
    config: BenchConfig,
    referenceRows: MatrixWorkload,
    matrix: MatrixWorkload,
    results: StorageBenchResults,
) {
    val text = buildString {
        appendLine("# krig macro storage bench")
        appendLine()
        appendLine("controlsEvents=${config.controlsEvents}, batch=${config.batchSize}")
        appendLine("referenceRows=${referenceRows.tags} tags x ${referenceRows.rows} rows, delta=${config.referenceDelta}")
        appendLine("matrix=${matrix.tags} tags x ${matrix.rows} rows")
        appendLine("timescale=${config.runTimescale}")
        appendLine()
        appendLine("## controls-kt reference")
        appendLine()
        appendLine("| scenario | values | write | read | bytes | bytes/value | note |")
        appendLine("|---|---:|---:|---:|---:|---:|---|")
        controlsReferences.forEach { appendLine(it.markdownRow()) }
        appendLine()
        appendLine("## controls-kt comparison run")
        appendLine()
        appendLine("| scenario | values | write | read | bytes | bytes/value | note |")
        appendLine("|---|---:|---:|---:|---:|---:|---|")
        controlsLocalRerun.forEach { appendLine(it.markdownRow()) }
        appendLine()
        appendLine("## krig Exposed profile")
        appendLine()
        appendLine("| scenario | rows | values | write | read | bytes | bytes/value | note |")
        appendLine("|---|---:|---:|---:|---:|---:|---:|---|")
        results.exposed.forEach { appendLine(it.markdownRow()) }
        appendLine()
        appendLine("## krig direct JDBC event baseline")
        appendLine()
        appendLine("| scenario | rows | values | write | read | bytes | bytes/value | note |")
        appendLine("|---|---:|---:|---:|---:|---:|---:|---|")
        results.jdbc.forEach { appendLine(it.markdownRow()) }
        appendLine()
        appendLine("## krig dense rows profile")
        appendLine()
        appendLine("| scenario | rows | values | write | read | bytes | bytes/value | note |")
        appendLine("|---|---:|---:|---:|---:|---:|---:|---|")
        results.dense.forEach { appendLine(it.markdownRow()) }
        appendLine()
        appendLine("## krig architecture profiles")
        appendLine()
        appendLine("| scenario | rows | values | write | read | bytes | bytes/value | note |")
        appendLine("|---|---:|---:|---:|---:|---:|---:|---|")
        results.architecture.forEach { appendLine(it.markdownRow()) }
        appendLine()
        appendLine("The controls-kt reference table comes from `feature/data-platform-storage` runs.")
        appendLine(
            "The controls-kt comparison table records the same branch and workload; " +
                "only the current rewrite-enabled Timescale test exists there.",
        )
        appendLine("Exposed rows compare the same ORM layer; direct JDBC rows show a lower storage-path bound.")
        appendLine("`*.event-json` rows use the controls-kt workload shape: one property, one source, 100 000 messages.")
        appendLine("`krig.reference-rows.*` uses the same dense rows shape as the controls-kt rows reference.")
        appendLine("Matrix scenarios are data-plane stress shapes, not event journal replacements.")
        appendLine("External JDBC can be enabled with `KRIG_STORAGE_BENCH_JDBC_URL`; Timescale with `KRIG_STORAGE_BENCH_TIMESCALE=true`.")
    }
    Files.writeString(path, text)
    println()
    println("Report: ${path.absolutePathString()}")
}
