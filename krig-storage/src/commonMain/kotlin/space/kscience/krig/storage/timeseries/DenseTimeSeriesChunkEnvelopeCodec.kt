package space.kscience.krig.storage.timeseries

import space.kscience.dataforge.io.Envelope
import space.kscience.dataforge.io.asBinary
import space.kscience.dataforge.io.toByteArray
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.int
import space.kscience.dataforge.meta.string
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.parseAsName
import space.kscience.krig.api.data.DataQuality
import space.kscience.krig.api.data.QualityCode
import space.kscience.krig.api.data.QualitySeverity
import kotlin.time.Instant

public enum class DenseTimeSeriesValueType(public val id: String, internal val bytesPerCell: Int) {
    Float64("float64", 8),
    Int32("int32", 4),
    Int64("int64", 8),
    Boolean("boolean", 1),
}

public object DenseTimeSeriesChunkEnvelopeSchema {
    public const val ENVELOPE_TYPE: String = "krig.dense-timeseries-chunk"
    public const val FORMAT_VERSION: Int = 1
    public const val TIME_LAYOUT: String = "row-major:epoch-seconds-int64+nanos-int32"
    public const val VALUE_LAYOUT: String = "column-major"
    public const val QUALITY_LAYOUT: String = "column-major:severity-int32+sparse-meta-details"

    public val VERSION: Name = "krig.timeseries.dense.version".parseAsName()
    public val VALUE_TYPE: Name = "krig.timeseries.dense.valueType".parseAsName()
    public val SERIES_COUNT: Name = "krig.timeseries.dense.seriesCount".parseAsName()
    public val ROW_COUNT: Name = "krig.timeseries.dense.rowCount".parseAsName()
    public val SERIES: Name = "krig.timeseries.dense.series".parseAsName()
    public val TIME_ENCODING: Name = "krig.timeseries.dense.time.encoding".parseAsName()
    public val VALUE_ENCODING: Name = "krig.timeseries.dense.value.encoding".parseAsName()
    public val QUALITY_ENCODING: Name = "krig.timeseries.dense.quality.encoding".parseAsName()
    public val QUALITY_DETAIL_COUNT: Name = "krig.timeseries.dense.quality.detailCount".parseAsName()
    public val QUALITY_DETAILS: Name = "krig.timeseries.dense.quality.details".parseAsName()
}

public interface DenseTimeSeriesChunkEnvelopeCodec<C : ColumnarTimeSeriesChunk> {
    public val valueType: DenseTimeSeriesValueType

    public fun encode(chunk: C): Envelope

    public fun decode(envelope: Envelope): C
}

public object DenseDoubleTimeSeriesChunkEnvelopeCodec :
    DenseTimeSeriesChunkEnvelopeCodec<DenseDoubleTimeSeriesChunk> {
    override val valueType: DenseTimeSeriesValueType = DenseTimeSeriesValueType.Float64

    override fun encode(chunk: DenseDoubleTimeSeriesChunk): Envelope =
        encodeDenseEnvelope(chunk, valueType) { writer ->
            for (seriesIndex in chunk.series.indices) {
                val column = chunk.column(seriesIndex)
                for (row in 0 until chunk.rowCount) writer.writeDouble(column[row])
            }
        }

    override fun decode(envelope: Envelope): DenseDoubleTimeSeriesChunk {
        val header = envelope.readHeader(valueType)
        val reader = ByteReader(envelope.payloadBytes())
        reader.readMagicAndCounts(header.series.size, header.rowCount)
        val times = reader.readTimes(header.rowCount)
        val columns = Array(header.series.size) { DoubleArray(header.rowCount) }
        for (seriesIndex in header.series.indices) {
            for (row in 0 until header.rowCount) columns[seriesIndex][row] = reader.readDouble()
        }
        val severityRanks = reader.readSeverityRanks(header.cellCount)
        reader.requireExhausted()
        return DenseDoubleTimeSeriesChunk(
            header.series,
            List(header.rowCount) { row ->
                DenseDoubleTimeSeriesRow(
                    time = times[row],
                    values = DoubleArray(header.series.size) { columns[it][row] },
                    qualityOverrides = qualityOverrides(row, header.series.size, header.rowCount, severityRanks, header.details),
                )
            },
        )
    }
}

public object DenseIntTimeSeriesChunkEnvelopeCodec :
    DenseTimeSeriesChunkEnvelopeCodec<DenseIntTimeSeriesChunk> {
    override val valueType: DenseTimeSeriesValueType = DenseTimeSeriesValueType.Int32

    override fun encode(chunk: DenseIntTimeSeriesChunk): Envelope =
        encodeDenseEnvelope(chunk, valueType) { writer ->
            for (seriesIndex in chunk.series.indices) {
                val column = chunk.column(seriesIndex)
                for (row in 0 until chunk.rowCount) writer.writeInt(column[row])
            }
        }

    override fun decode(envelope: Envelope): DenseIntTimeSeriesChunk {
        val header = envelope.readHeader(valueType)
        val reader = ByteReader(envelope.payloadBytes())
        reader.readMagicAndCounts(header.series.size, header.rowCount)
        val times = reader.readTimes(header.rowCount)
        val columns = Array(header.series.size) { IntArray(header.rowCount) }
        for (seriesIndex in header.series.indices) {
            for (row in 0 until header.rowCount) columns[seriesIndex][row] = reader.readInt()
        }
        val severityRanks = reader.readSeverityRanks(header.cellCount)
        reader.requireExhausted()
        return DenseIntTimeSeriesChunk(
            header.series,
            List(header.rowCount) { row ->
                DenseIntTimeSeriesRow(
                    time = times[row],
                    values = IntArray(header.series.size) { columns[it][row] },
                    qualityOverrides = qualityOverrides(row, header.series.size, header.rowCount, severityRanks, header.details),
                )
            },
        )
    }
}

public object DenseLongTimeSeriesChunkEnvelopeCodec :
    DenseTimeSeriesChunkEnvelopeCodec<DenseLongTimeSeriesChunk> {
    override val valueType: DenseTimeSeriesValueType = DenseTimeSeriesValueType.Int64

    override fun encode(chunk: DenseLongTimeSeriesChunk): Envelope =
        encodeDenseEnvelope(chunk, valueType) { writer ->
            for (seriesIndex in chunk.series.indices) {
                val column = chunk.column(seriesIndex)
                for (row in 0 until chunk.rowCount) writer.writeLong(column[row])
            }
        }

    override fun decode(envelope: Envelope): DenseLongTimeSeriesChunk {
        val header = envelope.readHeader(valueType)
        val reader = ByteReader(envelope.payloadBytes())
        reader.readMagicAndCounts(header.series.size, header.rowCount)
        val times = reader.readTimes(header.rowCount)
        val columns = Array(header.series.size) { LongArray(header.rowCount) }
        for (seriesIndex in header.series.indices) {
            for (row in 0 until header.rowCount) columns[seriesIndex][row] = reader.readLong()
        }
        val severityRanks = reader.readSeverityRanks(header.cellCount)
        reader.requireExhausted()
        return DenseLongTimeSeriesChunk(
            header.series,
            List(header.rowCount) { row ->
                DenseLongTimeSeriesRow(
                    time = times[row],
                    values = LongArray(header.series.size) { columns[it][row] },
                    qualityOverrides = qualityOverrides(row, header.series.size, header.rowCount, severityRanks, header.details),
                )
            },
        )
    }
}

public object DenseBooleanTimeSeriesChunkEnvelopeCodec :
    DenseTimeSeriesChunkEnvelopeCodec<DenseBooleanTimeSeriesChunk> {
    override val valueType: DenseTimeSeriesValueType = DenseTimeSeriesValueType.Boolean

    override fun encode(chunk: DenseBooleanTimeSeriesChunk): Envelope =
        encodeDenseEnvelope(chunk, valueType) { writer ->
            for (seriesIndex in chunk.series.indices) {
                val column = chunk.column(seriesIndex)
                for (row in 0 until chunk.rowCount) writer.writeBoolean(column[row])
            }
        }

    override fun decode(envelope: Envelope): DenseBooleanTimeSeriesChunk {
        val header = envelope.readHeader(valueType)
        val reader = ByteReader(envelope.payloadBytes())
        reader.readMagicAndCounts(header.series.size, header.rowCount)
        val times = reader.readTimes(header.rowCount)
        val columns = Array(header.series.size) { BooleanArray(header.rowCount) }
        for (seriesIndex in header.series.indices) {
            for (row in 0 until header.rowCount) columns[seriesIndex][row] = reader.readBoolean()
        }
        val severityRanks = reader.readSeverityRanks(header.cellCount)
        reader.requireExhausted()
        return DenseBooleanTimeSeriesChunk(
            header.series,
            List(header.rowCount) { row ->
                DenseBooleanTimeSeriesRow(
                    time = times[row],
                    values = BooleanArray(header.series.size) { columns[it][row] },
                    qualityOverrides = qualityOverrides(row, header.series.size, header.rowCount, severityRanks, header.details),
                )
            },
        )
    }
}

public fun DenseDoubleTimeSeriesChunk.toDenseTimeSeriesEnvelope(): Envelope =
    DenseDoubleTimeSeriesChunkEnvelopeCodec.encode(this)

public fun DenseIntTimeSeriesChunk.toDenseTimeSeriesEnvelope(): Envelope =
    DenseIntTimeSeriesChunkEnvelopeCodec.encode(this)

public fun DenseLongTimeSeriesChunk.toDenseTimeSeriesEnvelope(): Envelope =
    DenseLongTimeSeriesChunkEnvelopeCodec.encode(this)

public fun DenseBooleanTimeSeriesChunk.toDenseTimeSeriesEnvelope(): Envelope =
    DenseBooleanTimeSeriesChunkEnvelopeCodec.encode(this)

public fun Envelope.decodeDenseDoubleTimeSeriesChunk(): DenseDoubleTimeSeriesChunk =
    DenseDoubleTimeSeriesChunkEnvelopeCodec.decode(this)

public fun Envelope.decodeDenseIntTimeSeriesChunk(): DenseIntTimeSeriesChunk =
    DenseIntTimeSeriesChunkEnvelopeCodec.decode(this)

public fun Envelope.decodeDenseLongTimeSeriesChunk(): DenseLongTimeSeriesChunk =
    DenseLongTimeSeriesChunkEnvelopeCodec.decode(this)

public fun Envelope.decodeDenseBooleanTimeSeriesChunk(): DenseBooleanTimeSeriesChunk =
    DenseBooleanTimeSeriesChunkEnvelopeCodec.decode(this)

private const val MAGIC_0: Int = 'K'.code
private const val MAGIC_1: Int = 'D'.code
private const val MAGIC_2: Int = 'T'.code
private const val MAGIC_3: Int = 1

private data class DenseEnvelopeHeader(
    val series: List<Name>,
    val rowCount: Int,
    val details: Map<Int, DataQuality>,
) {
    val cellCount: Int get() = series.size * rowCount
}

private fun <C : ColumnarTimeSeriesChunk> encodeDenseEnvelope(
    chunk: C,
    valueType: DenseTimeSeriesValueType,
    writeValues: (ByteWriter) -> Unit,
): Envelope {
    val cellCount = chunk.series.size * chunk.rowCount
    val writer = ByteWriter(
        12 + chunk.rowCount * 12 + cellCount * valueType.bytesPerCell + cellCount * 4,
    )
    writer.writeMagicAndCounts(chunk.series.size, chunk.rowCount)
    for (time in chunk.times) {
        writer.writeLong(time.epochSeconds)
        writer.writeInt(time.nanosecondsOfSecond)
    }
    writeValues(writer)
    for (seriesIndex in chunk.series.indices) {
        val severity = chunk.severityColumn(seriesIndex)
        for (row in 0 until chunk.rowCount) writer.writeInt(severity[row])
    }
    return Envelope(chunk.toEnvelopeMeta(valueType), writer.toByteArray().asBinary())
}

private fun ColumnarTimeSeriesChunk.toEnvelopeMeta(valueType: DenseTimeSeriesValueType): Meta {
    val details = sparseQualityDetails()
    return Meta {
        Envelope.ENVELOPE_TYPE_KEY put DenseTimeSeriesChunkEnvelopeSchema.ENVELOPE_TYPE
        Envelope.ENVELOPE_DATA_TYPE_KEY put "application/vnd.krig.timeseries.dense.${valueType.id}+binary"
        DenseTimeSeriesChunkEnvelopeSchema.VERSION put DenseTimeSeriesChunkEnvelopeSchema.FORMAT_VERSION
        DenseTimeSeriesChunkEnvelopeSchema.VALUE_TYPE put valueType.id
        DenseTimeSeriesChunkEnvelopeSchema.SERIES_COUNT put series.size
        DenseTimeSeriesChunkEnvelopeSchema.ROW_COUNT put rowCount
        DenseTimeSeriesChunkEnvelopeSchema.TIME_ENCODING put DenseTimeSeriesChunkEnvelopeSchema.TIME_LAYOUT
        DenseTimeSeriesChunkEnvelopeSchema.VALUE_ENCODING put DenseTimeSeriesChunkEnvelopeSchema.VALUE_LAYOUT
        DenseTimeSeriesChunkEnvelopeSchema.QUALITY_ENCODING put DenseTimeSeriesChunkEnvelopeSchema.QUALITY_LAYOUT
        DenseTimeSeriesChunkEnvelopeSchema.QUALITY_DETAIL_COUNT put details.size
        series.forEachIndexed { index, name ->
            DenseTimeSeriesChunkEnvelopeSchema.SERIES.child(index) put name.toString()
        }
        details.entries.forEachIndexed { index, (cell, quality) ->
            val root = DenseTimeSeriesChunkEnvelopeSchema.QUALITY_DETAILS.child(index)
            root.child("cell") put cell
            root.child("severity") put quality.severity.rank
            quality.code?.let { root.child("code") put it.id }
            quality.detail?.let { root.child("detail") put it }
        }
    }
}

private fun ColumnarTimeSeriesChunk.sparseQualityDetails(): Map<Int, DataQuality> = buildMap {
    for (seriesIndex in series.indices) {
        val base = seriesIndex * rowCount
        for (row in 0 until rowCount) {
            val quality = qualityAt(row, seriesIndex)
            if (quality.code != null || quality.detail != null) put(base + row, quality)
        }
    }
}

private fun Envelope.readHeader(expectedType: DenseTimeSeriesValueType): DenseEnvelopeHeader {
    val version = meta.requireInt(DenseTimeSeriesChunkEnvelopeSchema.VERSION)
    require(version == DenseTimeSeriesChunkEnvelopeSchema.FORMAT_VERSION) {
        "Unsupported dense time-series envelope version $version."
    }
    val envelopeType = meta.requireString(Envelope.ENVELOPE_TYPE_KEY)
    require(envelopeType == DenseTimeSeriesChunkEnvelopeSchema.ENVELOPE_TYPE) {
        "Expected '${DenseTimeSeriesChunkEnvelopeSchema.ENVELOPE_TYPE}' envelope, got '$envelopeType'."
    }
    val valueType = meta.requireString(DenseTimeSeriesChunkEnvelopeSchema.VALUE_TYPE)
    require(valueType == expectedType.id) {
        "Expected dense value type '${expectedType.id}', got '$valueType'."
    }
    val seriesCount = meta.requireInt(DenseTimeSeriesChunkEnvelopeSchema.SERIES_COUNT)
    val rowCount = meta.requireInt(DenseTimeSeriesChunkEnvelopeSchema.ROW_COUNT)
    require(seriesCount >= 0) { "Series count must be non-negative, got $seriesCount." }
    require(rowCount >= 0) { "Row count must be non-negative, got $rowCount." }
    val series = List(seriesCount) { index ->
        meta.requireString(DenseTimeSeriesChunkEnvelopeSchema.SERIES.child(index)).parseAsName()
    }
    val detailCount = meta.requireInt(DenseTimeSeriesChunkEnvelopeSchema.QUALITY_DETAIL_COUNT)
    val details = buildMap {
        for (index in 0 until detailCount) {
            val root = DenseTimeSeriesChunkEnvelopeSchema.QUALITY_DETAILS.child(index)
            val cell = meta.requireInt(root.child("cell"))
            require(cell in 0 until seriesCount * rowCount) {
                "Dense quality detail cell $cell is outside 0 until ${seriesCount * rowCount}."
            }
            val severity = QualitySeverity(meta.requireInt(root.child("severity")))
            val code = meta[root.child("code")]?.string?.let(::QualityCode)
            val detail = meta[root.child("detail")]?.string
            put(cell, DataQuality(severity, code, detail))
        }
    }
    return DenseEnvelopeHeader(series, rowCount, details)
}

private fun qualityOverrides(
    row: Int,
    seriesCount: Int,
    rowCount: Int,
    severityRanks: IntArray,
    details: Map<Int, DataQuality>,
): Map<Int, DataQuality> = buildMap {
    for (seriesIndex in 0 until seriesCount) {
        val cell = seriesIndex * rowCount + row
        val quality = details[cell] ?: severityRanks[cell].let { rank ->
            if (rank == QualitySeverity.GOOD.rank) null else DataQuality(QualitySeverity(rank))
        }
        if (quality != null) put(seriesIndex, quality)
    }
}

private fun Envelope.payloadBytes(): ByteArray =
    data?.toByteArray() ?: error("Dense time-series envelope has no binary payload.")

private fun Meta.requireInt(key: Name): Int =
    get(key)?.int ?: error("Dense time-series envelope misses integer meta '$key'.")

private fun Meta.requireString(key: Name): String =
    get(key)?.string ?: error("Dense time-series envelope misses string meta '$key'.")

private fun Name.child(index: Int): Name = child(index.toString())

private fun Name.child(token: String): Name = Name(tokens + token.parseAsName().tokens)

private class ByteWriter(capacity: Int) {
    private val bytes = ByteArray(capacity)
    private var position: Int = 0

    fun writeMagicAndCounts(seriesCount: Int, rowCount: Int) {
        writeByte(MAGIC_0)
        writeByte(MAGIC_1)
        writeByte(MAGIC_2)
        writeByte(MAGIC_3)
        writeInt(seriesCount)
        writeInt(rowCount)
    }

    fun writeBoolean(value: Boolean) {
        writeByte(if (value) 1 else 0)
    }

    fun writeDouble(value: Double) {
        writeLong(value.toBits())
    }

    fun writeInt(value: Int) {
        writeByte(value ushr 24)
        writeByte(value ushr 16)
        writeByte(value ushr 8)
        writeByte(value)
    }

    fun writeLong(value: Long) {
        writeByte((value ushr 56).toInt())
        writeByte((value ushr 48).toInt())
        writeByte((value ushr 40).toInt())
        writeByte((value ushr 32).toInt())
        writeByte((value ushr 24).toInt())
        writeByte((value ushr 16).toInt())
        writeByte((value ushr 8).toInt())
        writeByte(value.toInt())
    }

    fun toByteArray(): ByteArray = bytes.copyOf(position)

    private fun writeByte(value: Int) {
        bytes[position++] = value.toByte()
    }
}

private class ByteReader(private val bytes: ByteArray) {
    private var position: Int = 0

    fun readMagicAndCounts(seriesCount: Int, rowCount: Int) {
        require(readUnsignedByte() == MAGIC_0 && readUnsignedByte() == MAGIC_1 &&
                readUnsignedByte() == MAGIC_2 && readUnsignedByte() == MAGIC_3) {
            "Dense time-series payload has invalid magic header."
        }
        val payloadSeries = readInt()
        val payloadRows = readInt()
        require(payloadSeries == seriesCount && payloadRows == rowCount) {
            "Dense payload dimensions $payloadSeries x $payloadRows do not match envelope $seriesCount x $rowCount."
        }
    }

    fun readTimes(rowCount: Int): List<Instant> = List(rowCount) {
        Instant.fromEpochSeconds(readLong(), readInt().toLong())
    }

    fun readBoolean(): Boolean = when (val value = readUnsignedByte()) {
        0 -> false
        1 -> true
        else -> error("Boolean cell must be encoded as 0 or 1, got $value.")
    }

    fun readDouble(): Double = Double.fromBits(readLong())

    fun readInt(): Int =
        (readUnsignedByte() shl 24) or
                (readUnsignedByte() shl 16) or
                (readUnsignedByte() shl 8) or
                readUnsignedByte()

    fun readLong(): Long {
        var result = 0L
        repeat(8) {
            result = (result shl 8) or readUnsignedByte().toLong()
        }
        return result
    }

    fun readSeverityRanks(cellCount: Int): IntArray = IntArray(cellCount) { readInt() }

    fun requireExhausted() {
        require(position == bytes.size) {
            "Dense payload has ${bytes.size - position} trailing bytes."
        }
    }

    private fun readUnsignedByte(): Int {
        require(position < bytes.size) { "Dense payload ended unexpectedly at byte $position." }
        return bytes[position++].toInt() and 0xFF
    }
}
