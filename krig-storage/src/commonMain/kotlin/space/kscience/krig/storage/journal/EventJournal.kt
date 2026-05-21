package space.kscience.krig.storage.journal

import kotlinx.coroutines.flow.Flow
import space.kscience.krig.api.messages.DeviceMessage
import space.kscience.krig.api.messages.MessageEnvelope
import space.kscience.krig.api.messages.envelope

/** Semantic event journal: replay, audit and causality, not high-rate time-series storage. */
public interface EventJournal {
    public suspend fun write(event: MessageEnvelope<DeviceMessage>)

    public fun readAll(): Flow<MessageEnvelope<DeviceMessage>>
}

public suspend fun EventJournal.write(message: DeviceMessage): Unit = write(message.envelope())
