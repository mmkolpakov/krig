@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package space.kscience.krig.core.state

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import space.kscience.dataforge.meta.MetaConverter
import space.kscience.dataforge.names.asName
import space.kscience.krig.api.addressing.Address
import space.kscience.krig.api.messages.DeviceMessage
import space.kscience.krig.api.messages.PropertyChangedMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Instant

class PropertyHistoryTest {
    @Test
    fun collectedHistoryKeepsSamplesWithoutActiveCollector() = runTest {
        val messages = MutableSharedFlow<DeviceMessage>()
        val history = collectPropertyHistory(
            scope = backgroundScope,
            messages = messages,
            deviceName = "thermo",
            propertyName = "temperature",
            converter = MetaConverter.double,
            maxSize = 4,
        )
        runCurrent()

        messages.emit(
            PropertyChangedMessage(
                time = Clock.System.now(),
                property = "temperature".asName(),
                value = MetaConverter.double.convert(23.5),
                sourceDevice = Address("", "thermo"),
            ),
        )
        runCurrent()

        val replayed = history
            .flowHistory(Instant.DISTANT_PAST, Instant.DISTANT_FUTURE)
            .take(1)
            .toList()

        assertEquals(listOf(23.5), replayed.map { it.value })
    }
}
