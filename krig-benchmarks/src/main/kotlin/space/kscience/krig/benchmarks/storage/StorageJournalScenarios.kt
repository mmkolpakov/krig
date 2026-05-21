package space.kscience.krig.benchmarks.storage

import java.sql.Connection
import java.sql.PreparedStatement
import kotlin.system.measureNanoTime
import kotlin.time.Instant
import kotlin.time.Duration.Companion.nanoseconds
import kotlinx.serialization.PolymorphicSerializer
import space.kscience.dataforge.meta.MetaConverter
import space.kscience.dataforge.names.asName
import space.kscience.krig.api.messages.DeviceMessage
import space.kscience.krig.api.messages.MessageEnvelope
import space.kscience.krig.api.messages.PropertyChangedMessage
import space.kscience.krig.api.messages.envelope
import space.kscience.krig.api.serialization.krigStorageJson

private val storageJson = krigStorageJson()
private val messageSerializer = PolymorphicSerializer(DeviceMessage::class)
private val envelopeSerializer = MessageEnvelope.serializer(messageSerializer)

internal enum class SqlBackend {
    H2,
    Postgres,
}

internal enum class JournalFormat {
    Payload,
    Envelope,
}

internal fun runControlsEventJournal(
    connection: Connection,
    backend: SqlBackend,
    events: Int,
    batchSize: Int,
    size: () -> Long?,
    scenario: String,
    note: String,
    afterCreate: () -> Unit = {},
    format: JournalFormat = JournalFormat.Payload,
): BenchResult {
    createControlsMessageTable(connection, backend)
    afterCreate()

    val source = "source".asName()
    val property = "prop".asName()
    val write = measureNanoTime {
        connection.prepareStatement(
            """
            INSERT INTO "DeviceMessages"("time", "sourceDevice", "targetDevice", "type", "content")
            VALUES(?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { ps ->
            var batched = 0
            repeat(events) { index ->
                val message = PropertyChangedMessage(
                    time = Instant.fromEpochMilliseconds(index.toLong()),
                    property = property,
                    value = MetaConverter.long.convert(index.toLong()),
                    sourceDevice = source,
                )
                ps.setObject(1, index.toOffsetDateTime())
                ps.setString(2, source.toString())
                ps.setString(3, null)
                ps.setString(4, message.messageType)
                ps.setString(5, encodeJournalMessage(message, format))
                ps.addBatch()
                batched++
                batched = flushBatchIfNeeded(connection, ps, batched, batchSize)
            }
            if (batched > 0) ps.executeBatch()
            connection.commit()
        }
    }.nanoseconds

    val readStats = readStoredMessages(
        connection,
        sql = """SELECT "content" FROM "DeviceMessages" ORDER BY "time"""",
        format = format,
    ) { message -> MetaConverter.long.read(message.value).toDouble() }

    check(readStats.count == events) { "read ${readStats.count} messages, expected $events" }
    return BenchResult(
        scenario = scenario,
        rows = events,
        values = events,
        write = write,
        read = readStats.duration,
        bytes = size(),
        note = "$note, checksum=${readStats.checksum.toLong()}",
    )
}

internal fun runMatrixEventJournal(
    connection: Connection,
    workload: MatrixWorkload,
    batchSize: Int,
    size: () -> Long?,
    scenario: String,
): BenchResult {
    connection.statement {
        execute("DROP TABLE IF EXISTS message_journal")
        execute(
            """
            CREATE TABLE message_journal(
                time_ms BIGINT NOT NULL,
                source_device VARCHAR(255) NOT NULL,
                property_name VARCHAR(255) NOT NULL,
                message_type VARCHAR(255) NOT NULL,
                content CLOB NOT NULL
            )
            """.trimIndent(),
        )
        execute("CREATE INDEX message_journal_time_idx ON message_journal(time_ms)")
    }

    val source = "bench.device".asName()
    val write = measureNanoTime {
        connection.prepareStatement(
            "INSERT INTO message_journal(time_ms, source_device, property_name, message_type, content) VALUES(?, ?, ?, ?, ?)",
        ).use { ps ->
            var batched = 0
            for (row in 0 until workload.rows) {
                for (tag in 0 until workload.tags) {
                    val property = tagName(tag)
                    val message = propertyMessage(row, tag, property, source, workload)
                    ps.setLong(1, row.toLong() * 1_000L)
                    ps.setString(2, source.toString())
                    ps.setString(3, property.toString())
                    ps.setString(4, message.messageType)
                    ps.setString(5, encodeJournalMessage(message, JournalFormat.Payload))
                    ps.addBatch()
                    batched++
                    batched = flushBatchIfNeeded(connection, ps, batched, batchSize)
                }
            }
            if (batched > 0) ps.executeBatch()
            connection.commit()
        }
    }.nanoseconds

    val readStats = readStoredMessages(
        connection,
        sql = "SELECT content FROM message_journal ORDER BY time_ms, property_name",
        format = JournalFormat.Payload,
    ) { message -> MetaConverter.double.read(message.value) }

    check(readStats.count == workload.values) { "read ${readStats.count} messages, expected ${workload.values}" }
    return BenchResult(
        scenario = scenario,
        rows = workload.values,
        values = workload.values,
        write = write,
        read = readStats.duration,
        bytes = size(),
        note = "${workload.id}, compact DeviceMessage JSON, checksum=${readStats.checksum.toInt()}",
    )
}

private fun createControlsMessageTable(connection: Connection, backend: SqlBackend) {
    val contentType = when (backend) {
        SqlBackend.H2 -> "CLOB"
        SqlBackend.Postgres -> "TEXT"
    }
    connection.statement {
        execute("""DROP TABLE IF EXISTS "DeviceMessages"""")
        execute(
            """
            CREATE TABLE "DeviceMessages"(
                "time" TIMESTAMP WITH TIME ZONE NOT NULL,
                "sourceDevice" VARCHAR(255),
                "targetDevice" VARCHAR(255),
                "type" VARCHAR(255) NOT NULL,
                "content" $contentType NOT NULL
            )
            """.trimIndent(),
        )
        execute("""CREATE INDEX "DeviceMessages_time_idx" ON "DeviceMessages"("time")""")
    }
}

internal fun flushBatchIfNeeded(
    connection: Connection,
    ps: PreparedStatement,
    batched: Int,
    batchSize: Int,
): Int =
    if (batched == batchSize) {
        ps.executeBatch()
        connection.commit()
        0
    } else {
        batched
    }

private fun readStoredMessages(
    connection: Connection,
    sql: String,
    format: JournalFormat,
    value: (PropertyChangedMessage) -> Double,
): ReadStats {
    var count = 0
    var checksum = 0.0
    val duration = measureNanoTime {
        connection.prepareStatement(sql).use { ps ->
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    val message = decodeJournalMessage(rs.getString(1), format)
                    checksum += value(message as PropertyChangedMessage)
                    count++
                }
            }
        }
    }.nanoseconds
    return ReadStats(duration, count, checksum)
}

internal fun encodeJournalMessage(message: DeviceMessage, format: JournalFormat): String =
    when (format) {
        JournalFormat.Payload -> storageJson.encodeToString(messageSerializer, message)
        JournalFormat.Envelope -> storageJson.encodeToString(envelopeSerializer, message.envelope())
    }

internal fun decodeJournalMessage(content: String, format: JournalFormat): DeviceMessage =
    when (format) {
        JournalFormat.Payload -> storageJson.decodeFromString(messageSerializer, content)
        JournalFormat.Envelope -> storageJson.decodeFromString(envelopeSerializer, content).payload
    }
