# Module controls-persistence-log

**Maturity**: PROTOTYPE

## Description

This module provides a high-performance, persistent implementation of the `AuditLogService` contract, built on top of SQLDelight and an embedded SQLite database.

## Key Features

-   **High-Performance Queries**: By storing device messages in a structured SQLite database with proper indexing, this implementation allows for fast, efficient querying of historical data, even for very large logs.
-   **Data Integrity**: Leverages SQLite's transactional guarantees to ensure that log entries are written atomically and safely, preventing data corruption.
-   **Type-Safe API**: Uses SQLDelight to generate a type-safe Kotlin API for all database interactions, eliminating runtime errors related to SQL syntax or data types.
-   **Multiplatform Support**: Provides a common implementation that works seamlessly across JVM and Native platforms.
-   **Pluggable Architecture**: Implemented as a DataForge plugin (`SqlDelightAuditLogPlugin`), allowing it to be easily configured and swapped in as the `AuditLogService` provider in a `controls-ng` runtime.

## Usage

This module is typically not used directly in application code but is configured as a plugin within the main `Context`. The runtime will then automatically discover and use this service.

**Example Configuration:**

```kotlin
// In your main context setup
plugin(SqlDelightAuditLogPlugin) {
    // Path to the SQLite database file.
    "dbPath" put "./audit.db"
}
```