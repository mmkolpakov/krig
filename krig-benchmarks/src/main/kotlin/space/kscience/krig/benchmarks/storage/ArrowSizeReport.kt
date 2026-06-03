package space.kscience.krig.benchmarks.storage

import space.kscience.dataforge.names.asName
import space.kscience.krig.arrow.ArrowCompression

/**
 * Standalone Arrow file-size report (run with `--add-opens=java.base/java.nio=ALL-UNNAMED` and
 * `--enable-native-access=ALL-UNNAMED`).
 *
 * Prints uncompressed / LZ4 / ZSTD Arrow IPC sizes for a synthetic dense chunk so the compression
 * ratio is visible without a JMH harness. This is the honest "size" companion to
 * [ArrowExportBenchmark]; a head-to-head against the row-compression storage path is future work.
 */
public fun main() {
    val series = List(32) { "pv$it".asName() }
    val chunk = denseExportChunk(series, rowCount = 16_384)
    val rawDoubles = chunk.rows.size * series.size * Long.SIZE_BYTES

    println("Dense chunk: ${chunk.rows.size} rows x ${series.size} series (~$rawDoubles raw value bytes)")
    for (compression in ArrowCompression.entries) {
        val bytes = exportSize(chunk, compression)
        val ratio = bytes.toDouble() / rawDoubles
        println("  ${compression.name.padEnd(5)} -> $bytes bytes (${"%.3f".format(ratio)} x raw doubles)")
    }
}
