package space.kscience.krig.benchmarks.storage

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.isRegularFile

internal data class JdbcTarget(val url: String, val user: String, val password: String)

internal fun externalJdbcTarget(): JdbcTarget? {
    val url = System.getenv("KRIG_STORAGE_BENCH_JDBC_URL") ?: return null
    return JdbcTarget(
        url = url,
        user = System.getenv("KRIG_STORAGE_BENCH_JDBC_USER").orEmpty(),
        password = System.getenv("KRIG_STORAGE_BENCH_JDBC_PASSWORD").orEmpty(),
    )
}

internal fun connect(target: JdbcTarget): Connection =
    DriverManager.getConnection(target.url, target.user, target.password)

internal fun tableBytes(connection: Connection, table: String): Long? = runCatching {
    connection.prepareStatement("SELECT pg_total_relation_size(?)").use { ps ->
        ps.setString(1, table)
        ps.executeQuery().use { rs ->
            if (rs.next()) rs.getLong(1) else null
        }
    }
}.getOrNull()

internal inline fun Connection.statement(block: java.sql.Statement.() -> Unit) {
    createStatement().use { it.block() }
    commit()
}

@OptIn(ExperimentalPathApi::class)
internal fun prepareRoot(root: Path) {
    if (root.exists()) root.deleteRecursively()
    root.createDirectories()
}

internal fun deleteH2Files(base: Path) {
    base.resolveSibling("${base.fileName}.mv.db").deleteIfExists()
    base.resolveSibling("${base.fileName}.trace.db").deleteIfExists()
}

internal fun h2Bytes(base: Path): Long =
    Files.list(base.parent).use { stream ->
        stream.filter { it.isRegularFile() && it.fileName.toString().startsWith(base.fileName.toString()) }
            .mapToLong { it.fileSize() }
            .sum()
    }

internal fun File.directorySize(): Long =
    walkTopDown().filter { it.isFile }.sumOf { it.length() }

internal fun envInt(name: String): Int? = System.getenv(name)?.toIntOrNull()

internal fun String?.toBooleanEnv(): Boolean = when (this?.lowercase()) {
    null -> false
    "1", "true", "yes", "on" -> true
    "0", "false", "no", "off" -> false
    else -> false
}

internal fun Int.toOffsetDateTime(): OffsetDateTime =
    OffsetDateTime.ofInstant(java.time.Instant.ofEpochMilli(toLong()), ZoneOffset.UTC)
