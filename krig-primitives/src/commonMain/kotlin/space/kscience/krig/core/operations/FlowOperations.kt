package space.kscience.krig.core.operations

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import space.kscience.krig.api.data.Timestamped
import space.kscience.krig.api.messages.PropertyChangedMessage
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.Name
import kotlin.time.Clock

/** Wraps each element in a [Timestamped] with current timestamp. */
public fun <T> Flow<T>.withTimestamp(clock: Clock = Clock.System): Flow<Timestamped<T>> =
    map { Timestamped(it, clock.now()) }

/** Converts each Meta value into a [PropertyChangedMessage]. */
public fun Flow<Meta>.asDeviceEvents(
    deviceName: Name,
    propertyName: Name,
    clock: Clock = Clock.System,
): Flow<PropertyChangedMessage> =
    map {
        PropertyChangedMessage(
            sourceDevice = deviceName,
            property = propertyName,
            value = it,
            time = clock.now(),
        )
    }
