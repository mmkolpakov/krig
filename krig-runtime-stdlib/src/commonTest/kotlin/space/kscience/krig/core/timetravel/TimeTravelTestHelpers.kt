package space.kscience.krig.core.timetravel

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import space.kscience.krig.api.messages.DeviceMessage
import space.kscience.krig.api.messages.DeviceMessageFrame
import space.kscience.krig.api.messages.frame

internal fun Flow<DeviceMessage>.testEnvelopes(): Flow<DeviceMessageFrame<DeviceMessage>> =
    map { it.testEnvelope() }

internal fun DeviceMessage.testEnvelope(): DeviceMessageFrame<DeviceMessage> =
    frame()
