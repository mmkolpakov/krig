package space.kscience.krig.arrow

import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.Float8Vector
import org.apache.arrow.vector.IntVector
import org.apache.arrow.vector.TimeStampNanoVector
import org.apache.arrow.vector.VarCharVector
import org.apache.arrow.vector.ipc.ArrowFileReader
import space.kscience.dataforge.names.asName
import space.kscience.krig.api.data.DataQuality
import space.kscience.krig.api.data.QualityCode
import space.kscience.krig.api.data.QualitySeverity
import space.kscience.krig.storage.timeseries.DenseDoubleTimeSeriesChunk
import space.kscience.krig.storage.timeseries.DenseDoubleTimeSeriesRow
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class ArrowRoundTripTest {

    @Test
    fun roundTripPreservesValuesAndPerCellQuality() {
        val a = "a".asName()
        val b = "b".asName()
        val chunk = DenseDoubleTimeSeriesChunk(
            series = listOf(a, b),
            rows = listOf(
                DenseDoubleTimeSeriesRow(Instant.fromEpochMilliseconds(0), doubleArrayOf(1.0, 2.0)),
                DenseDoubleTimeSeriesRow(
                    time = Instant.fromEpochMilliseconds(1_000),
                    values = doubleArrayOf(3.0, 4.0),
                    baselineQuality = DataQuality(QualitySeverity.UNCERTAIN, QualityCode("opcua.stale"), "slow"),
                    qualityOverrides = mapOf(1 to DataQuality(QualitySeverity.BAD, QualityCode("modbus.timeout"))),
                ),
            ),
        )

        val file = Files.createTempFile("krig-arrow", ".arrow")
        try {
            chunk.writeArrowIpcFile(file, ArrowCompression.ZSTD)

            RootAllocator(Long.MAX_VALUE).use { allocator ->
                ArrowFileReader(Files.newByteChannel(file), allocator).use { reader ->
                    assertTrue(reader.loadNextBatch(), "expected one record batch")
                    val root = reader.vectorSchemaRoot
                    assertEquals(2, root.rowCount)

                    val timeVector = root.getVector("time") as TimeStampNanoVector
                    assertEquals(0L, timeVector.get(0))
                    assertEquals(1_000L * 1_000_000L, timeVector.get(1))

                    val valueA = root.getVector("a") as Float8Vector
                    val valueB = root.getVector("b") as Float8Vector
                    assertEquals(1.0, valueA.get(0))
                    assertEquals(3.0, valueA.get(1))
                    assertEquals(2.0, valueB.get(0))
                    assertEquals(4.0, valueB.get(1))

                    val severityA = root.getVector("a::quality.severity") as IntVector
                    val severityB = root.getVector("b::quality.severity") as IntVector
                    assertEquals(QualitySeverity.GOOD.rank, severityA.get(0))
                    assertEquals(QualitySeverity.UNCERTAIN.rank, severityA.get(1))
                    assertEquals(QualitySeverity.BAD.rank, severityB.get(1))

                    val codeA = root.getVector("a::quality.code") as VarCharVector
                    val codeB = root.getVector("b::quality.code") as VarCharVector
                    assertTrue(codeA.isNull(0), "row 0 has no quality code")
                    assertEquals("opcua.stale", String(codeA.get(1), Charsets.UTF_8))
                    assertEquals("modbus.timeout", String(codeB.get(1), Charsets.UTF_8))

                    val detailA = root.getVector("a::quality.detail") as VarCharVector
                    assertEquals("slow", String(detailA.get(1), Charsets.UTF_8))
                }
            }
        } finally {
            Files.deleteIfExists(file)
        }
    }
}
