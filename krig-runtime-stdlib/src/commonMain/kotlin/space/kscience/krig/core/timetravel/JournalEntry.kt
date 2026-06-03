package space.kscience.krig.core.timetravel

import kotlinx.serialization.Serializable
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.Name
import space.kscience.krig.api.messages.MessageContext
import space.kscience.krig.storage.journal.EventCursor
import kotlin.time.Instant

/**
 * Raw journal row. Storage backends persist this shape; codecs turn it into replay messages.
 */
@Serializable
public data class JournalEntry(
    public val subject: Name,
    public val messageType: String,
    public val schema: StorageSchema,
    public val time: Instant,
    public val payload: Meta,
    public val context: MessageContext = MessageContext.Empty,
)

/** Cursor plus raw [JournalEntry]. */
public data class JournalRecord(
    public val cursor: EventCursor,
    public val entry: JournalEntry,
)
