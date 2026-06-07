package space.kscience.krig.arrow

import org.apache.arrow.compression.CommonsCompressionFactory
import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.FieldVector
import org.apache.arrow.vector.Float8Vector
import org.apache.arrow.vector.IntVector
import org.apache.arrow.vector.TimeStampNanoVector
import org.apache.arrow.vector.VarCharVector
import org.apache.arrow.vector.VectorSchemaRoot
import org.apache.arrow.vector.compression.CompressionUtil
import org.apache.arrow.vector.ipc.ArrowFileWriter
import org.apache.arrow.vector.ipc.message.IpcOption
import space.kscience.krig.storage.timeseries.DenseDoubleTimeSeriesChunk
import java.nio.channels.FileChannel
import java.nio.channels.WritableByteChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Compression codec applied to the Arrow IPC record-batch body.
 *
 * [ZSTD] is the default: it yields small files with very fast decompression and is the most
 * widely supported choice in the analytical ecosystem (pandas, Polars, DuckDB, Spark).
 */
public enum class ArrowCompression {
    NONE,
    ZSTD,
    LZ4,
}

private const val NANOS_PER_SECOND: Long = 1_000_000_000L
private const val COLUMNS_PER_SERIES: Int = 4
private const val QUALITY_SEVERITY_SUFFIX: String = "::quality.severity"
private const val QUALITY_CODE_SUFFIX: String = "::quality.code"
private const val QUALITY_DETAIL_SUFFIX: String = "::quality.detail"

/** Rows per Arrow record batch; bounds peak off-heap memory regardless of total chunk size. */
private const val DEFAULT_BATCH_SIZE: Int = 8_192
private const val MIN_ALLOCATOR_BYTES: Long = 64L * 1024 * 1024
private const val PER_CELL_BYTE_BUDGET: Long = 1_024

/**
 * Writes this dense telemetry chunk to an Apache Arrow IPC file (`.arrow`) at [path].
 *
 * The schema is `time` (timestamp, ns) plus, per series, a `Float8` value column and a lossless
 * data-quality triplet (`severity`, `code`, `detail`) so per-cell quality overrides survive the
 * round-trip. Rows are streamed in [batchSize]-row record batches that reuse the same vectors, so
 * peak off-heap memory stays bounded regardless of the total chunk size.
 */
public fun DenseDoubleTimeSeriesChunk.writeArrowIpcFile(
    path: Path,
    compression: ArrowCompression = ArrowCompression.ZSTD,
    batchSize: Int = DEFAULT_BATCH_SIZE,
) {
    path.toAbsolutePath().parent?.let(Files::createDirectories)
    FileChannel.open(
        path,
        StandardOpenOption.CREATE,
        StandardOpenOption.WRITE,
        StandardOpenOption.TRUNCATE_EXISTING,
    ).use { channel -> writeArrowIpc(channel, compression, batchSize) }
}

/**
 * Feather V2 is exactly the Arrow IPC file format on disk; this is a naming alias over
 * [writeArrowIpcFile] for callers who think in terms of the `.feather` ecosystem.
 */
public fun DenseDoubleTimeSeriesChunk.writeFeatherV2(
    path: Path,
    compression: ArrowCompression = ArrowCompression.ZSTD,
    batchSize: Int = DEFAULT_BATCH_SIZE,
): Unit = writeArrowIpcFile(path, compression, batchSize)

/**
 * Writes the Arrow IPC file body to an arbitrary [channel] (used by sinks, benchmarks, streams).
 * Emits one record batch per [batchSize] rows; the allocator is capped to a finite budget so a
 * runaway export fails fast instead of exhausting native memory.
 */
public fun DenseDoubleTimeSeriesChunk.writeArrowIpc(
    channel: WritableByteChannel,
    compression: ArrowCompression = ArrowCompression.ZSTD,
    batchSize: Int = DEFAULT_BATCH_SIZE,
) {
    require(batchSize > 0) { "batchSize must be positive, got $batchSize" }
    RootAllocator(allocatorLimitBytes(batchSize, series.size)).use { allocator ->
        val vectors = ArrowSeriesVectors(this, allocator)
        VectorSchemaRoot(vectors.fieldVectors).use { root ->
            newFileWriter(root, channel, compression).use { writer ->
                writeBatches(root, writer, vectors, batchSize)
            }
        }
    }
}

/** Streams every record batch through [writer], reusing [vectors] across batches. */
private fun DenseDoubleTimeSeriesChunk.writeBatches(
    root: VectorSchemaRoot,
    writer: ArrowFileWriter,
    vectors: ArrowSeriesVectors,
    batchSize: Int,
) {
    writer.start()
    var start = 0
    while (start < rowCount) {
        val count = minOf(batchSize, rowCount - start)
        vectors.fieldVectors.forEach { it.reset() }
        fillBatch(vectors, start, count)
        root.rowCount = count
        writer.writeBatch()
        start += count
    }
    writer.end()
}

/** Populates [count] rows of [vectors] starting at row [start] from the column store. */
private fun DenseDoubleTimeSeriesChunk.fillBatch(
    vectors: ArrowSeriesVectors,
    start: Int,
    count: Int,
) {
    for (i in 0 until count) {
        val rowIndex = start + i
        val time = times[rowIndex]
        vectors.timeVector.setSafe(i, time.epochSeconds * NANOS_PER_SECOND + time.nanosecondsOfSecond)
        for (column in series.indices) {
            vectors.valueVectors[column].setSafe(i, value(rowIndex, column))
            val quality = qualityAt(rowIndex, column)
            vectors.severityVectors[column].setSafe(i, quality.severity.rank)
            setNullableUtf8(vectors.codeVectors[column], i, quality.code?.id)
            setNullableUtf8(vectors.detailVectors[column], i, quality.detail)
        }
    }
}

/**
 * Arrow vectors backing one export: a `time` column plus, per series, a `Float8` value column and a
 * data-quality triplet. Allocated once per [writeArrowIpc] call and reused across record batches.
 */
private class ArrowSeriesVectors(chunk: DenseDoubleTimeSeriesChunk, allocator: RootAllocator) {
    val timeVector: TimeStampNanoVector = TimeStampNanoVector("time", allocator)
    val valueVectors: List<Float8Vector> = chunk.series.map { Float8Vector(it.toString(), allocator) }
    val severityVectors: List<IntVector> =
        chunk.series.map { IntVector(it.toString() + QUALITY_SEVERITY_SUFFIX, allocator) }
    val codeVectors: List<VarCharVector> =
        chunk.series.map { VarCharVector(it.toString() + QUALITY_CODE_SUFFIX, allocator) }
    val detailVectors: List<VarCharVector> =
        chunk.series.map { VarCharVector(it.toString() + QUALITY_DETAIL_SUFFIX, allocator) }
    val fieldVectors: List<FieldVector> = buildList(1 + chunk.series.size * COLUMNS_PER_SERIES) {
        add(timeVector)
        for (column in chunk.series.indices) {
            add(valueVectors[column])
            add(severityVectors[column])
            add(codeVectors[column])
            add(detailVectors[column])
        }
    }
}

/** Finite native-memory budget for one record batch, scaled by batch size and column count. */
private fun allocatorLimitBytes(batchSize: Int, seriesCount: Int): Long {
    val columns = 1L + seriesCount.toLong() * COLUMNS_PER_SERIES
    return maxOf(MIN_ALLOCATOR_BYTES, batchSize.toLong() * columns * PER_CELL_BYTE_BUDGET)
}

private fun setNullableUtf8(vector: VarCharVector, index: Int, value: String?) {
    if (value == null) vector.setNull(index) else vector.setSafe(index, value.toByteArray(Charsets.UTF_8))
}

private fun newFileWriter(
    root: VectorSchemaRoot,
    channel: WritableByteChannel,
    compression: ArrowCompression,
): ArrowFileWriter = when (compression) {
    ArrowCompression.NONE -> ArrowFileWriter(root, null, channel)
    ArrowCompression.ZSTD -> ArrowFileWriter(
        root, null, channel, null, IpcOption(),
        CommonsCompressionFactory.INSTANCE, CompressionUtil.CodecType.ZSTD,
    )
    ArrowCompression.LZ4 -> ArrowFileWriter(
        root, null, channel, null, IpcOption(),
        CommonsCompressionFactory.INSTANCE, CompressionUtil.CodecType.LZ4_FRAME,
    )
}
