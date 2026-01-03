package space.kscience.controls.composite.persistence.log

import app.cash.sqldelight.ColumnAdapter
import space.kscience.controls.api.identifiers.CorrelationId
import kotlin.time.Instant

/**
 * A SQLDelight `ColumnAdapter` for `kotlin.time.Instant`, storing it as a Unix timestamp
 * in milliseconds (`Long`) in the database. This approach is efficient for storage and querying.
 */
internal object InstantAdapter : ColumnAdapter<Instant, Long> {
    /**
     * Decodes a `Long` value from the database into an `Instant`.
     *
     * @param databaseValue The Unix timestamp in milliseconds.
     * @return The corresponding `Instant` object.
     */
    override fun decode(databaseValue: Long): Instant = Instant.fromEpochMilliseconds(databaseValue)

    /**
     * Encodes an `Instant` into a `Long` value for database storage.
     *
     * @param value The `Instant` object.
     * @return The Unix timestamp in milliseconds.
     */
    override fun encode(value: Instant): Long = value.toEpochMilliseconds()
}

/**
 * A SQLDelight `ColumnAdapter` for the type-safe `CorrelationId` value class,
 * mapping it to and from its underlying `String` representation for database storage.
 */
internal object CorrelationIdAdapter : ColumnAdapter<CorrelationId, String> {
    /**
     * Decodes a `String` from the database into a `CorrelationId`.
     *
     * @param databaseValue The string representation of the correlation ID.
     * @return The type-safe `CorrelationId` object.
     */
    override fun decode(databaseValue: String): CorrelationId = CorrelationId(databaseValue)

    /**
     * Encodes a `CorrelationId` into its raw `String` value for database storage.
     *
     * @param value The `CorrelationId` object.
     * @return The raw string ID.
     */
    override fun encode(value: CorrelationId): String = value.id
}