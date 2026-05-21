package space.kscience.krig.benchmarks.storage

import kotlin.system.measureNanoTime
import kotlin.time.Instant
import kotlin.time.Duration.Companion.nanoseconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.datetime.timestamp
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import space.kscience.dataforge.meta.MetaConverter
import space.kscience.dataforge.names.asName
import space.kscience.krig.api.messages.PropertyChangedMessage

private object ExposedMessages : Table("DeviceMessages") {
    val time: Column<Instant> = timestamp("time").index()
    val sourceDevice: Column<String?> = varchar("sourceDevice", 255).nullable()
    val targetDevice: Column<String?> = varchar("targetDevice", 255).nullable()
    val type: Column<String> = varchar("type", 255)
    val content: Column<String> = text("content")
}

internal fun runExposedEventJournal(
    url: String,
    backend: SqlBackend,
    events: Int,
    batchSize: Int,
    pageSize: Int,
    size: () -> Long?,
    scenario: String,
    note: String,
    afterCreate: (Database) -> Unit = {},
): BenchResult {
    val database = exposedDatabase(url, backend)
    transaction(database) {
        SchemaUtils.drop(ExposedMessages)
        SchemaUtils.create(ExposedMessages)
    }
    afterCreate(database)

    val messages = exposedMessages(events)
    val write = measureNanoTime {
        runBlocking(Dispatchers.IO) {
            messages.chunked(batchSize).forEach { chunk ->
                suspendTransaction(database) {
                    ExposedMessages.batchInsert(chunk) { message ->
                        this[ExposedMessages.time] = message.time
                        this[ExposedMessages.sourceDevice] = message.sourceDevice.toString()
                        this[ExposedMessages.targetDevice] = message.targetDevice?.toString()
                        this[ExposedMessages.type] = message.messageType
                        this[ExposedMessages.content] = encodeJournalMessage(message, JournalFormat.Payload)
                    }
                }
            }
        }
    }.nanoseconds

    val readStats = readExposedMessages(database, pageSize)
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

private fun exposedDatabase(url: String, backend: SqlBackend): Database =
    when (backend) {
        SqlBackend.H2 -> Database.connect(url = url)
        SqlBackend.Postgres -> Database.connect(
            url = url,
            driver = "org.postgresql.Driver",
            user = "test",
            password = "test",
        )
    }

private fun exposedMessages(count: Int): List<PropertyChangedMessage> {
    val source = "source".asName()
    val property = "prop".asName()
    return List(count) { index ->
        PropertyChangedMessage(
            time = Instant.fromEpochMilliseconds(index.toLong()),
            property = property,
            value = MetaConverter.long.convert(index.toLong()),
            sourceDevice = source,
        )
    }
}

private fun readExposedMessages(database: Database, pageSize: Int): ReadStats {
    val messages = exposedMessageFlow(database, pageSize)
    var count = 0
    var checksum = 0.0
    val values = measureNanoTime {
        runBlocking(Dispatchers.IO) { messages.toList() }.forEach { message ->
            checksum += MetaConverter.long.read(message.value).toDouble()
            count++
        }
    }.nanoseconds
    return ReadStats(values, count, checksum)
}

private fun exposedMessageFlow(database: Database, pageSize: Int): Flow<PropertyChangedMessage> = flow {
    var lastPageBottomTime: Instant? = null
    while (true) {
        val page = suspendTransaction(database, readOnly = true) {
            ExposedMessages.selectAll()
                .orderBy(ExposedMessages.time, SortOrder.DESC)
                .limit(pageSize)
                .apply {
                    lastPageBottomTime?.let { bottom ->
                        andWhere { ExposedMessages.time less bottom }
                    }
                }
                .map { row -> decodeJournalMessage(row[ExposedMessages.content], JournalFormat.Payload) }
                .filterIsInstance<PropertyChangedMessage>()
        }
        page.forEach { emit(it) }
        if (page.size < pageSize) break
        lastPageBottomTime = page.last().time
    }
}
