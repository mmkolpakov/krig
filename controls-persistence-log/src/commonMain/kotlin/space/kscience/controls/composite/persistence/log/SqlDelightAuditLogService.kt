package space.kscience.controls.composite.persistence.log

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import space.kscience.controls.core.messages.DeviceMessage
import space.kscience.controls.core.identifiers.CorrelationId
import space.kscience.controls.core.serialization.json
import space.kscience.controls.services.AuditLogQuery
import space.kscience.controls.services.AuditLogService
import space.kscience.dataforge.context.AbstractPlugin
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.context.PluginTag
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.string

public class SqlDelightAuditLogService(
    context: Context,
    private val driver: SqlDriver,
    meta: Meta = Meta.EMPTY,
) :
    AbstractPlugin(meta),
    AuditLogService,
    CoroutineScope by context {

    override val tag: PluginTag get() = AuditLogService.tag

    private val json: Json = context.json

    private val db = AppDatabase(
        driver = driver,
        audit_logAdapter = Audit_log.Adapter(
            timestampAdapter = InstantAdapter,
            correlation_idAdapter = CorrelationIdAdapter,
            payloadAdapter = DeviceMessageAdapter(json)
        ),
    )

    override suspend fun record(message: DeviceMessage) {
        db.appDatabaseQueries.insert_audit_log(
            timestamp = message.time,
            source_device_hub = message.sourceDevice?.route?.toString() ?: "UNKNOWN",
            source_device_name = message.sourceDevice?.device?.toString() ?: "UNKNOWN",
            target_device_hub = message.targetDevice?.route?.toString() ?: "UNKNOWN",
            target_device_name = message.targetDevice?.device?.toString() ?: "UNKNOWN",
            message_type = message::class.simpleName ?: "UNKNOWN",
            correlation_id = message.correlationId,
            payload = message,
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun query(query: AuditLogQuery): Flow<DeviceMessage> {
        val resultsFlow = db.appDatabaseQueries.select_payload_audit_log(
            startTime = query.startTime,
            endTime = query.endTime,
            deviceNamePattern = query.filterMeta[AuditLogQuery.DEVICE_NAME_PATTERN_KEY]?.string,
            messageType = query.filterMeta[AuditLogQuery.MESSAGE_TYPE_KEY]?.string,
            correlationId = query.filterMeta[AuditLogQuery.CORRELATION_ID_KEY]?.string?.let { CorrelationId(it) },
        ).asFlow().mapToList(Dispatchers.IO)
        return resultsFlow.flatMapConcat { it.asFlow() }.flowOn(Dispatchers.IO)
    }
}