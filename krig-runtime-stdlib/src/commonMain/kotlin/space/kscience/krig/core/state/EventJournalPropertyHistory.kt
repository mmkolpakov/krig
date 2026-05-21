package space.kscience.krig.core.state

import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import space.kscience.dataforge.meta.MetaConverter
import space.kscience.dataforge.names.Name
import space.kscience.krig.api.data.Timestamped
import space.kscience.krig.api.messages.PropertyChangedMessage
import space.kscience.krig.storage.journal.EventJournal
import space.kscience.krig.storage.journal.read

/**
 * Storage-backed [PropertyHistory] over the semantic event journal.
 */
public fun <T> EventJournal.propertyHistory(
    device: Name,
    property: Name,
    converter: MetaConverter<T>,
): PropertyHistory<T> = PropertyHistory { from, until ->
    read<PropertyChangedMessage>(
        range = from..until,
        sourceDevice = device,
    )
        .filter { it.property == property }
        .map { message ->
            Timestamped(
                value = converter.read(message.value),
                time = message.time,
            )
        }
}
