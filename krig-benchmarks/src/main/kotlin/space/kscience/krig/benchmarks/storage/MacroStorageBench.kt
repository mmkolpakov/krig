package space.kscience.krig.benchmarks.storage

import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import org.testcontainers.containers.BindMode
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import space.kscience.krig.storage.profile.StorageProfiles

fun main() {
    val config = BenchConfig()
    prepareRoot(config.root)

    val referenceRows = MatrixWorkload.randomWalk(
        id = "reference-rows",
        tags = config.referenceRowsTags,
        rows = config.referenceRowsRows,
        seed = 0,
        stepFrom = -0.1,
        stepUntil = 0.1,
    )
    val matrix = MatrixWorkload.deterministic("matrix", config.matrixTags, config.matrixRows)

    printHeader(config, referenceRows, matrix)

    val results = storageBench(config) {
        h2ExposedJournal()
        h2JdbcJournal()
        compatibleRows(referenceRows)
        h2Matrix(matrix)
        denseRows(matrix)
        optionalTimescaleExposedJournal()
        optionalTimescaleJdbcJournal()
        optionalExternalJdbc(matrix)
    }

    printReference()
    printResults("krig Exposed profile", results.exposed)
    printResults("krig direct JDBC event baseline", results.jdbc)
    printResults("krig dense rows profile", results.dense)
    printResults("krig architecture profiles", results.architecture)
    writeReport(config.root.resolve("storage-results.md"), config, referenceRows, matrix, results)
}

private fun printHeader(config: BenchConfig, referenceRows: MatrixWorkload, matrix: MatrixWorkload) {
    println("krig macro storage bench")
    println("controlsEvents=${config.controlsEvents}, batch=${config.batchSize}")
    println("referenceRows=${referenceRows.tags} tags x ${referenceRows.rows} rows, delta=${config.referenceDelta}")
    println("matrix=${matrix.tags} tags x ${matrix.rows} rows")
}

internal fun runH2ExposedJournal(config: BenchConfig): List<BenchResult> {
    val h2Root = config.root.resolve("h2")
    h2Root.createDirectories()
    return listOf(
        withH2Url(h2Root, "exposed-event-json") { url, size ->
            runExposedEventJournal(
                url = url,
                backend = SqlBackend.H2,
                events = config.controlsEvents,
                batchSize = config.batchSize,
                pageSize = 1_000,
                size = size,
                scenario = "krig.exposed.h2.event-json",
                note = "controls-shaped workload, Exposed + krig DeviceMessage JSON",
            )
        },
    )
}

internal fun runH2JdbcJournal(config: BenchConfig): List<BenchResult> {
    val h2Root = config.root.resolve("h2")
    h2Root.createDirectories()
    return listOf(
        withH2(h2Root, "jdbc-event-json") { connection, size ->
            runControlsEventJournal(
                connection = connection,
                backend = SqlBackend.H2,
                events = config.controlsEvents,
                batchSize = config.batchSize,
                size = size,
                scenario = "krig.jdbc.h2.event-json",
                note = "controls-shaped workload, direct JDBC + krig DeviceMessage JSON",
            )
        },
        withH2(h2Root, "jdbc-event-envelope-json") { connection, size ->
            runControlsEventJournal(
                connection = connection,
                backend = SqlBackend.H2,
                events = config.controlsEvents,
                batchSize = config.batchSize,
                size = size,
                scenario = "krig.jdbc.h2.event-envelope-json",
                note = "same workload, direct JDBC + ${StorageProfiles.JournalCompact} envelope",
                format = JournalFormat.Envelope,
            )
        },
    )
}

internal fun runH2Matrix(config: BenchConfig, workload: MatrixWorkload): List<BenchResult> {
    val h2Root = config.root.resolve("h2")
    h2Root.createDirectories()
    return listOf(
        withH2(h2Root, "matrix-event-json") { connection, size ->
            runMatrixEventJournal(connection, workload, config.batchSize, size, "krig.h2.matrix-event-json")
        },
        withH2(h2Root, "typed-narrow") { connection, size ->
            runTypedNarrow(connection, workload, config.batchSize, size, "krig.h2.typed-narrow")
        },
        withH2(h2Root, "typed-wide") { connection, size ->
            runTypedWide(connection, workload, size, "krig.h2.typed-wide")
        },
    )
}

internal fun runTimescaleExposedJournal(config: BenchConfig): List<BenchResult> = listOf(
    runTimescaleExposedJournal(config, rewrite = false),
    runTimescaleExposedJournal(config, rewrite = true),
)

private fun runTimescaleExposedJournal(config: BenchConfig, rewrite: Boolean): BenchResult {
    val scenario = if (rewrite) {
        "krig.exposed.timescale.event-json.rewrite"
    } else {
        "krig.exposed.timescale.event-json"
    }
    return withTimescale(config, scenario, rewrite) { url, size ->
        runExposedEventJournal(
            url = url,
            backend = SqlBackend.Postgres,
            events = config.controlsEvents,
            batchSize = if (rewrite) 5_000 else config.batchSize,
            pageSize = 50_000,
            size = size,
            scenario = scenario,
            note = "TimescaleDB + Exposed ${if (rewrite) "with" else "without"} reWriteBatchedInserts",
            afterCreate = { database ->
                org.jetbrains.exposed.v1.jdbc.transactions.transaction(database) {
                    exec("SELECT create_hypertable('DeviceMessages', 'time', if_not_exists => TRUE)")
                }
            },
        )
    }
}

internal fun runTimescaleJdbcJournal(config: BenchConfig): List<BenchResult> = listOf(
    runTimescaleJdbcJournal(config, rewrite = false),
    runTimescaleJdbcJournal(config, rewrite = true),
)

private fun runTimescaleJdbcJournal(config: BenchConfig, rewrite: Boolean): BenchResult {
    val scenario = if (rewrite) {
        "krig.jdbc.timescale.event-json.rewrite"
    } else {
        "krig.jdbc.timescale.event-json"
    }
    return withTimescale(config, scenario, rewrite) { url, size ->
        DriverManager.getConnection(url, "test", "test").use { connection ->
            connection.autoCommit = false
            runControlsEventJournal(
                connection = connection,
                backend = SqlBackend.Postgres,
                events = config.controlsEvents,
                batchSize = if (rewrite) 5_000 else config.batchSize,
                size = size,
                scenario = scenario,
                note = "TimescaleDB + direct JDBC ${if (rewrite) "with" else "without"} reWriteBatchedInserts",
                afterCreate = {
                    connection.statement {
                        execute("SELECT create_hypertable('\"DeviceMessages\"', 'time', if_not_exists => TRUE)")
                    }
                },
            )
        }
    }
}

private fun withTimescale(
    config: BenchConfig,
    scenario: String,
    rewrite: Boolean,
    block: (url: String, size: () -> Long?) -> BenchResult,
): BenchResult {
    val dataDir = config.root.resolve("timescale/$scenario").toFile()
    if (dataDir.exists()) dataDir.deleteRecursively()
    dataDir.mkdirs()

    val image = DockerImageName.parse(config.timescaleImage).asCompatibleSubstituteFor("postgres")
    val container = PostgreSQLContainer(image).apply {
        withDatabaseName("test")
        withUsername("test")
        withPassword("test")
        withFileSystemBind(dataDir.absolutePath, "/var/lib/postgresql/data", BindMode.READ_WRITE)
    }

    container.start()
    return try {
        val before = dataDir.directorySize()
        val url = if (rewrite) "${container.jdbcUrl}&reWriteBatchedInserts=true" else container.jdbcUrl
        val result = block(url) {
            DriverManager.getConnection(url, container.username, container.password).use { connection ->
                tableBytes(connection, "DeviceMessages")
            }
        }
        container.stop()
        val delta = (dataDir.directorySize() - before).coerceAtLeast(0)
        result.copy(bytes = delta, note = "${result.note}, dataDirDelta=$delta")
    } finally {
        if (container.isRunning) container.stop()
    }
}

internal fun runExternalJdbc(
    config: BenchConfig,
    workload: MatrixWorkload,
    target: JdbcTarget,
): List<BenchResult> =
    connect(target).use { connection ->
        connection.autoCommit = false
        listOf(
            runMatrixEventJournal(
                connection = connection,
                workload = workload,
                batchSize = config.batchSize,
                size = { tableBytes(connection, "message_journal") },
                scenario = "jdbc.matrix-event-json",
            ),
            runTypedNarrow(
                connection = connection,
                workload = workload,
                batchSize = config.batchSize,
                size = { tableBytes(connection, "timeseries_narrow") },
                scenario = "jdbc.typed-narrow",
            ),
        )
    }

private fun withH2(
    root: Path,
    name: String,
    block: (Connection, () -> Long?) -> BenchResult,
): BenchResult {
    val dbBase = root.resolve(name)
    deleteH2Files(dbBase)
    val url = h2Url(dbBase)
    return DriverManager.getConnection(url).use { connection ->
        connection.autoCommit = false
        val result = block(connection) { h2Bytes(dbBase) }
        connection.createStatement().use { it.execute("SHUTDOWN") }
        result.copy(bytes = h2Bytes(dbBase))
    }
}

private fun withH2Url(
    root: Path,
    name: String,
    block: (url: String, () -> Long?) -> BenchResult,
): BenchResult {
    val dbBase = root.resolve(name)
    deleteH2Files(dbBase)
    val result = block(h2Url(dbBase)) { h2Bytes(dbBase) }
    return result.copy(bytes = h2Bytes(dbBase))
}

private fun h2Url(dbBase: Path): String =
    "jdbc:h2:file:${dbBase.absolutePathString().replace('\\', '/')};MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
