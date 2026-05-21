package space.kscience.krig.benchmarks.storage

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.fileSize
import kotlin.math.abs
import kotlin.system.measureNanoTime
import kotlin.time.Duration.Companion.nanoseconds

internal fun runTypedNarrow(
    connection: Connection,
    workload: MatrixWorkload,
    batchSize: Int,
    size: () -> Long?,
    scenario: String,
): BenchResult {
    connection.statement {
        execute("DROP TABLE IF EXISTS timeseries_narrow")
        execute(
            """
            CREATE TABLE timeseries_narrow(
                time_ms BIGINT NOT NULL,
                tag VARCHAR(255) NOT NULL,
                sample_value DOUBLE PRECISION NOT NULL
            )
            """.trimIndent(),
        )
        execute("CREATE INDEX timeseries_narrow_time_idx ON timeseries_narrow(time_ms)")
    }

    val write = measureNanoTime {
        connection.prepareStatement("INSERT INTO timeseries_narrow(time_ms, tag, sample_value) VALUES(?, ?, ?)").use { ps ->
            var batched = 0
            for (row in 0 until workload.rows) {
                for (tag in 0 until workload.tags) {
                    val sample = timeSeriesSample(row, tag, workload)
                    ps.setLong(1, sample.time.toEpochMilliseconds())
                    ps.setString(2, sample.series.toString())
                    ps.setDouble(3, sample.value)
                    ps.addBatch()
                    batched++
                    batched = flushBatchIfNeeded(connection, ps, batched, batchSize)
                }
            }
            if (batched > 0) ps.executeBatch()
            connection.commit()
        }
    }.nanoseconds

    val readStats = readNarrow(connection)
    return BenchResult(
        scenario = scenario,
        rows = workload.values,
        values = workload.values,
        write = write,
        read = readStats.duration,
        bytes = size(),
        note = "${workload.id}, typed tag/value rows, checksum=${readStats.checksum.toInt()}",
    )
}

internal fun runTypedWide(
    connection: Connection,
    workload: MatrixWorkload,
    size: () -> Long?,
    scenario: String,
): BenchResult {
    connection.statement {
        execute("DROP TABLE IF EXISTS timeseries_wide")
        execute(
            buildString {
                append("CREATE TABLE timeseries_wide(time_ms BIGINT NOT NULL")
                repeat(workload.tags) { tag -> append(", v$tag DOUBLE PRECISION NOT NULL") }
                append(")")
            },
        )
        execute("CREATE INDEX timeseries_wide_time_idx ON timeseries_wide(time_ms)")
    }

    val columns = buildString {
        append("time_ms")
        repeat(workload.tags) { append(", v$it") }
    }
    val placeholders = buildString {
        append("?")
        repeat(workload.tags) { append(", ?") }
    }

    val write = measureNanoTime {
        connection.prepareStatement("INSERT INTO timeseries_wide($columns) VALUES($placeholders)").use { ps ->
            for (row in 0 until workload.rows) {
                ps.setLong(1, row.toLong() * 1_000L)
                for (tag in 0 until workload.tags) ps.setDouble(tag + 2, workload.valueAt(row, tag))
                ps.addBatch()
                if ((row + 1) % 10 == 0) {
                    ps.executeBatch()
                    connection.commit()
                }
            }
            ps.executeBatch()
            connection.commit()
        }
    }.nanoseconds

    var count = 0
    var checksum = 0.0
    val read = measureNanoTime {
        connection.prepareStatement("SELECT * FROM timeseries_wide ORDER BY time_ms").use { ps ->
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    for (tag in 0 until workload.tags) {
                        checksum += rs.getDouble(tag + 2)
                        count++
                    }
                }
            }
        }
    }.nanoseconds

    check(count == workload.values) { "read $count values, expected ${workload.values}" }
    return BenchResult(
        scenario = scenario,
        rows = workload.rows,
        values = workload.values,
        write = write,
        read = read,
        bytes = size(),
        note = "${workload.id}, wide rows, checksum=${checksum.toInt()}",
    )
}

internal fun runChunkScenarios(
    config: BenchConfig,
    workload: MatrixWorkload,
    prefix: String,
    deadband: Double,
): List<BenchResult> = listOf(
    runJsonRowsChunk(config, workload, "$prefix.json-rows.deflate"),
    runBinaryRowsChunk(config, workload, "$prefix.binary-rows.deflate"),
    runBinaryDeadbandChunk(config, workload, "$prefix.binary-deadband.deflate", deadband),
)

private fun runJsonRowsChunk(
    config: BenchConfig,
    workload: MatrixWorkload,
    scenario: String,
): BenchResult {
    val file = config.root.resolve("chunks/$scenario")
    file.parent.createDirectories()
    file.deleteIfExists()

    val write = measureNanoTime {
        OutputStreamWriter(DeflaterOutputStream(Files.newOutputStream(file)), Charsets.UTF_8).use { writer ->
            writer.append('[')
            for (row in 0 until workload.rows) {
                if (row > 0) writer.append(',')
                writer.append("{\"timeMs\":").append((row * 1_000L).toString())
                for (tag in 0 until workload.tags) {
                    writer.append(",\"tag").append(tag.toString()).append("\":")
                    writer.append(workload.valueAt(row, tag).toString())
                }
                writer.append('}')
            }
            writer.append(']')
        }
    }.nanoseconds

    var chars = 0
    val read = measureNanoTime {
        InputStreamReader(InflaterInputStream(Files.newInputStream(file)), Charsets.UTF_8).use { reader ->
            val buffer = CharArray(16 * 1024)
            while (true) {
                val n = reader.read(buffer)
                if (n < 0) break
                chars += n
            }
        }
    }.nanoseconds

    return BenchResult(
        scenario = scenario,
        rows = workload.rows,
        values = workload.values,
        write = write,
        read = read,
        bytes = file.fileSize(),
        note = "${workload.id}, JSON rows, inflatedChars=$chars",
    )
}

private fun runBinaryRowsChunk(
    config: BenchConfig,
    workload: MatrixWorkload,
    scenario: String,
): BenchResult {
    val file = config.root.resolve("chunks/$scenario")
    file.parent.createDirectories()
    file.deleteIfExists()
    val chunk = workload.denseDoubleChunk()

    val write = measureNanoTime {
        DataOutputStream(DeflaterOutputStream(Files.newOutputStream(file))).use { out ->
            out.writeInt(chunk.series.size)
            out.writeInt(chunk.rows.size)
            chunk.rows.forEach { row ->
                out.writeLong(row.time.toEpochMilliseconds())
                row.values.forEach(out::writeDouble)
            }
        }
    }.nanoseconds
    val readStats = readBinaryRowsChunk(file)

    return BenchResult(
        scenario = scenario,
        rows = workload.rows,
        values = workload.values,
        write = write,
        read = readStats.duration,
        bytes = file.fileSize(),
        note = "${workload.id}, typed binary rows, checksum=${readStats.checksum.toInt()}",
    )
}

private fun runBinaryDeadbandChunk(
    config: BenchConfig,
    workload: MatrixWorkload,
    scenario: String,
    deadband: Double,
): BenchResult {
    val file = config.root.resolve("chunks/$scenario")
    file.parent.createDirectories()
    file.deleteIfExists()
    val chunk = workload.denseDoubleChunk()

    var storedValues = 0
    val write = measureNanoTime {
        val previous = DoubleArray(chunk.series.size) { Double.NaN }
        DataOutputStream(DeflaterOutputStream(Files.newOutputStream(file))).use { out ->
            out.writeInt(chunk.series.size)
            out.writeInt(chunk.rows.size)
            out.writeDouble(deadband)
            chunk.rows.forEach { row ->
                val changed = IntArray(chunk.series.size)
                val values = DoubleArray(chunk.series.size)
                var count = 0
                row.values.forEachIndexed { tag, value ->
                    if (previous[tag].isNaN() || abs(value - previous[tag]) > deadband) {
                        changed[count] = tag
                        values[count] = value
                        previous[tag] = value
                        count++
                    }
                }
                out.writeLong(row.time.toEpochMilliseconds())
                out.writeInt(count)
                for (i in 0 until count) {
                    out.writeInt(changed[i])
                    out.writeDouble(values[i])
                }
                storedValues += count
            }
        }
    }.nanoseconds
    val readStats = readBinaryDeadbandChunk(file)

    return BenchResult(
        scenario = scenario,
        rows = workload.rows,
        values = workload.values,
        write = write,
        read = readStats.duration,
        bytes = file.fileSize(),
        note = "${workload.id}, deadband=$deadband, stored=$storedValues, checksum=${readStats.checksum.toInt()}",
    )
}

private fun readBinaryRowsChunk(file: Path): ReadStats {
    var count = 0
    var checksum = 0.0
    val duration = measureNanoTime {
        DataInputStream(InflaterInputStream(Files.newInputStream(file))).use { input ->
            val tags = input.readInt()
            val rows = input.readInt()
            repeat(rows) {
                input.readLong()
                repeat(tags) {
                    checksum += input.readDouble()
                    count++
                }
            }
        }
    }.nanoseconds
    return ReadStats(duration, count, checksum)
}

private fun readBinaryDeadbandChunk(file: Path): ReadStats {
    var count = 0
    var checksum = 0.0
    val duration = measureNanoTime {
        DataInputStream(InflaterInputStream(Files.newInputStream(file))).use { input ->
            input.readInt()
            val rows = input.readInt()
            input.readDouble()
            repeat(rows) {
                input.readLong()
                val changed = input.readInt()
                repeat(changed) {
                    input.readInt()
                    checksum += input.readDouble()
                    count++
                }
            }
        }
    }.nanoseconds
    return ReadStats(duration, count, checksum)
}

private fun readNarrow(connection: Connection): ReadStats {
    var count = 0
    var checksum = 0.0
    val duration = measureNanoTime {
        connection.prepareStatement("SELECT time_ms, tag, sample_value FROM timeseries_narrow ORDER BY time_ms, tag").use { ps ->
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    checksum += rs.getDouble(3)
                    count++
                }
            }
        }
    }.nanoseconds
    return ReadStats(duration, count, checksum)
}
