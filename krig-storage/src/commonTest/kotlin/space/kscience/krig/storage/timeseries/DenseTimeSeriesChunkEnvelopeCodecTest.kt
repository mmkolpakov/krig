package space.kscience.krig.storage.timeseries

import space.kscience.dataforge.io.Envelope
import space.kscience.dataforge.io.toByteArray
import space.kscience.dataforge.meta.int
import space.kscience.dataforge.meta.string
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName
import space.kscience.krig.api.data.DataQuality
import space.kscience.krig.api.data.QualityCode
import space.kscience.krig.api.data.QualitySeverity
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Instant

class DenseTimeSeriesChunkEnvelopeCodecTest {
    private val rpm = "rpm".asName()
    private val load = "load".asName()
    private val bad = DataQuality(QualitySeverity.BAD, QualityCode("sensor.bad"), detail = "sensor fault")
    private val uncertain = DataQuality(QualitySeverity.UNCERTAIN)

    @Test
    fun doubleChunkRoundTripsThroughEnvelope() {
        val chunk = DenseDoubleTimeSeriesChunk(
            series = listOf(rpm, load),
            rows = listOf(
                DenseDoubleTimeSeriesRow(Instant.fromEpochMilliseconds(0), doubleArrayOf(900.0, 0.3)),
                DenseDoubleTimeSeriesRow(
                    Instant.fromEpochMilliseconds(10),
                    doubleArrayOf(910.0, 0.31),
                    qualityOverrides = mapOf(0 to uncertain, 1 to bad),
                ),
            ),
        )

        val envelope = chunk.toDenseTimeSeriesEnvelope()
        val decoded = envelope.decodeDenseDoubleTimeSeriesChunk()

        envelope.assertEnvelopeHeader(DenseTimeSeriesValueType.Float64, seriesCount = 2, rowCount = 2)
        assertEquals(0L, decoded.times.first().toEpochMilliseconds())
        assertContentEquals(doubleArrayOf(900.0, 910.0), decoded.column(0).asArray())
        assertContentEquals(doubleArrayOf(0.3, 0.31), decoded.column(1).asArray())
        assertEquals(QualitySeverity.UNCERTAIN, decoded.qualityAt(1, 0).severity)
        assertEquals(bad, decoded.qualityAt(1, 1))
    }

    @Test
    fun intLongAndBooleanChunksRoundTripThroughEnvelope() {
        val intChunk = DenseIntTimeSeriesChunk(
            series = listOf(rpm, load),
            rows = listOf(
                DenseIntTimeSeriesRow(Instant.fromEpochMilliseconds(1), intArrayOf(1, 2)),
                DenseIntTimeSeriesRow(Instant.fromEpochMilliseconds(2), intArrayOf(3, 4), qualityOverrides = mapOf(1 to bad)),
            ),
        )
        val longChunk = DenseLongTimeSeriesChunk(
            series = listOf(rpm, load),
            rows = listOf(
                DenseLongTimeSeriesRow(Instant.fromEpochMilliseconds(1), longArrayOf(10L, 20L)),
                DenseLongTimeSeriesRow(Instant.fromEpochMilliseconds(2), longArrayOf(30L, 40L)),
            ),
        )
        val booleanChunk = DenseBooleanTimeSeriesChunk(
            series = listOf(rpm, load),
            rows = listOf(
                DenseBooleanTimeSeriesRow(Instant.fromEpochMilliseconds(1), booleanArrayOf(true, false)),
                DenseBooleanTimeSeriesRow(
                    Instant.fromEpochMilliseconds(2),
                    booleanArrayOf(false, true),
                    qualityOverrides = mapOf(0 to uncertain),
                ),
            ),
        )

        val decodedInt = intChunk.toDenseTimeSeriesEnvelope().decodeDenseIntTimeSeriesChunk()
        val decodedLong = longChunk.toDenseTimeSeriesEnvelope().decodeDenseLongTimeSeriesChunk()
        val decodedBoolean = booleanChunk.toDenseTimeSeriesEnvelope().decodeDenseBooleanTimeSeriesChunk()

        assertContentEquals(intArrayOf(1, 3), decodedInt.column(0))
        assertContentEquals(intArrayOf(2, 4), decodedInt.column(1))
        assertEquals(bad, decodedInt.qualityAt(1, 1))
        assertContentEquals(longArrayOf(10L, 30L), decodedLong.column(0))
        assertContentEquals(longArrayOf(20L, 40L), decodedLong.column(1))
        assertContentEquals(booleanArrayOf(true, false), decodedBoolean.column(0))
        assertContentEquals(booleanArrayOf(false, true), decodedBoolean.column(1))
        assertEquals(QualitySeverity.UNCERTAIN, decodedBoolean.qualityAt(1, 0).severity)
    }

    @Test
    fun decoderRejectsWrongPrimitiveFamily() {
        val envelope = DenseIntTimeSeriesChunk(
            series = listOf(rpm),
            rows = listOf(DenseIntTimeSeriesRow(Instant.fromEpochMilliseconds(1), intArrayOf(1))),
        ).toDenseTimeSeriesEnvelope()

        assertFailsWith<IllegalArgumentException> {
            envelope.decodeDenseDoubleTimeSeriesChunk()
        }
    }

    private fun Envelope.assertEnvelopeHeader(
        valueType: DenseTimeSeriesValueType,
        seriesCount: Int,
        rowCount: Int,
    ) {
        assertEquals(
            DenseTimeSeriesChunkEnvelopeSchema.ENVELOPE_TYPE,
            meta[Envelope.ENVELOPE_TYPE_KEY]?.string,
        )
        assertEquals(valueType.id, meta[DenseTimeSeriesChunkEnvelopeSchema.VALUE_TYPE]?.string)
        assertEquals(seriesCount, meta[DenseTimeSeriesChunkEnvelopeSchema.SERIES_COUNT]?.int)
        assertEquals(rowCount, meta[DenseTimeSeriesChunkEnvelopeSchema.ROW_COUNT]?.int)
        assertEquals("rpm", meta[DenseTimeSeriesChunkEnvelopeSchema.SERIES.childForTest(0)]?.string)
        assertEquals(1, meta[DenseTimeSeriesChunkEnvelopeSchema.QUALITY_DETAIL_COUNT]?.int)
        assertEquals(84, data?.toByteArray()?.size)
    }
}

private fun space.kscience.kmath.structures.Float64Buffer.asArray(): DoubleArray =
    DoubleArray(size) { this[it] }

private fun Name.childForTest(index: Int): Name = Name(tokens + index.toString().asName().tokens)
