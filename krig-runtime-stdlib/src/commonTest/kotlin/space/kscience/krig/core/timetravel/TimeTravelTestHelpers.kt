package space.kscience.krig.core.timetravel

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import space.kscience.krig.api.messages.DeviceMessage
import space.kscience.krig.api.messages.DeviceMessageEnvelope
import space.kscience.krig.api.messages.envelope

internal fun Flow<DeviceMessage>.testEnvelopes(): Flow<DeviceMessageEnvelope<DeviceMessage>> =
    map { it.testEnvelope() }

internal fun DeviceMessage.testEnvelope(): DeviceMessageEnvelope<DeviceMessage> =
    envelope()
