@file:Suppress("unused")

package space.kscience.krig.benchmarks.storage

import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.Blackhole
import kotlinx.benchmark.Param
import kotlinx.benchmark.Scope
import kotlinx.benchmark.State
import space.kscience.dataforge.names.asName
import space.kscience.krig.arrow.ArrowCompression

/**
 * Bounded Arrow IPC export at growing row counts up to one million rows. Guards against
 * unbounded heap growth (OOM regression) on the JVM analytics path. Run with a capped heap,
 * e.g. `-Xmx512m`, plus `--add-opens=java.base/java.nio=ALL-UNNAMED` and
 * `--enable-native-access=ALL-UNNAMED`.
 */
@State(Scope.Benchmark)
open class ArrowExportStressBenchmark {
    @Param("16384", "262144", "1000000")
    var rowCount: Int = 0

    private val series = List(8) { "pv$it".asName() }

    @Benchmark
    open fun exportZstd(blackhole: Blackhole): Int =
        exportSize(denseExportChunk(series, rowCount), ArrowCompression.ZSTD).also(blackhole::consume)
}
