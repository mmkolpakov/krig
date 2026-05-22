package space.kscience.krig.core.timetravel

import kotlinx.serialization.Serializable
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.Name
import space.kscience.krig.api.messages.MessageContext
import kotlin.jvm.JvmInline
import kotlin.time.Instant

/** Schema id for raw journal entries. */
@JvmInline
@Serializable
public value class JournalSchema(public val value: String) {
    init { require(value.isNotBlank()) { "JournalSchema must not be blank" } }
}

public object JournalSchemas {
    public val deviceMessageV1: JournalSchema = JournalSchema("krig.device-message.v1")
}

/**
 * Raw journal row. Storage backends persist this shape; codecs turn it into replay messages.
 */
@Serializable
public data class JournalEntry(
    public val subject: Name,
    public val messageType: String,
    public val schema: JournalSchema,
    public val time: Instant,
    public val payload: Meta,
    public val context: MessageContext = MessageContext.Empty,
)

/** Cursor plus raw [JournalEntry]. */
public data class JournalRecord(
    public val cursor: EventCursor,
    public val entry: JournalEntry,
)
