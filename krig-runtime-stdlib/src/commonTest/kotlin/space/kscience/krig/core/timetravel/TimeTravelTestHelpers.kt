package space.kscience.krig.core.timetravel

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.asValue
import space.kscience.dataforge.meta.int
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName
import space.kscience.krig.api.data.DeviceSnapshot
import space.kscience.krig.api.messages.DeviceMessage
import space.kscience.krig.api.messages.DeviceMessageFrame
import space.kscience.krig.api.messages.PropertyChangedMessage
import space.kscience.krig.api.messages.frame
import space.kscience.krig.core.contracts.Device
import space.kscience.krig.storage.journal.ReplayLog
import kotlin.time.Instant

internal val counterSource: Name = "lab.counter".asName()

internal fun Flow<DeviceMessage>.testEnvelopes(): Flow<DeviceMessageFrame<DeviceMessage>> =
    map { it.testEnvelope() }

internal fun DeviceMessage.testEnvelope(): DeviceMessageFrame<DeviceMessage> =
    frame()

internal class CounterReplay : DeviceReconstructible<Device> {
    val applied: MutableList<Int> = mutableListOf()

    var value: Int = 0
        private set

    override suspend fun applyEvent(event: DeviceMessage) {
        val message = event as? PropertyChangedMessage ?: return
        if (message.property == "value".asName()) {
            value = message.value.int ?: value
            applied += value
        }
    }

    override suspend fun captureSnapshot(at: Instant): DeviceSnapshot =
        DeviceSnapshot(at = at, state = Meta(value.asValue()))

    override suspend fun restoreSnapshot(snapshot: DeviceSnapshot) {
        value = snapshot.state.int ?: error("malformed snapshot")
    }
}

internal fun counterEvent(
    t: Long,
    value: Int,
    source: Name = counterSource,
): PropertyChangedMessage = PropertyChangedMessage(
    time = Instant.fromEpochMilliseconds(t),
    sourceDevice = source,
    property = "value".asName(),
    value = Meta(value.asValue()),
)

internal fun counterReplayLog(vararg events: PropertyChangedMessage): ReplayLog =
    ReplayLog(flowOf(*events).testEnvelopes())
