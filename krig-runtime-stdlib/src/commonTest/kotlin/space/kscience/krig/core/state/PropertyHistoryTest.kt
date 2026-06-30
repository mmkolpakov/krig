@file:OptIn(
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
    space.kscience.krig.core.UnstableKrigForSubclassing::class,
)

package space.kscience.krig.core.state

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.meta.MetaConverter
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName
import space.kscience.krig.api.messages.DeviceMessage
import space.kscience.krig.api.messages.PropertyChangedMessage
import space.kscience.krig.api.result.OperationOutcome
import space.kscience.krig.core.contracts.AbstractDevice
import space.kscience.krig.core.contracts.DeviceRuntime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.time.Clock
import kotlin.time.Instant

class PropertyHistoryTest {
    private class HistoryDevice(
        name: String,
    ) : AbstractDevice(
        name.asName(),
        DeviceRuntime(Context("history-$name")),
    ) {
        override suspend fun doReadPropertyOutcome(propertyName: Name): OperationOutcome<Meta> =
            OperationOutcome.Ok(Meta.EMPTY)
    }

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
                sourceDevice = "thermo".asName(),
            ),
        )
        runCurrent()

        val replayed = history
            .flowHistory(Instant.DISTANT_PAST, Instant.DISTANT_FUTURE)
            .take(1)
            .toList()

        assertEquals(listOf(23.5), replayed.map { it.value })
    }

    @Test
    fun deviceHistoryReusesCollectorForSameOptions() {
        val device = HistoryDevice("thermo")
        try {
            val first = device.propertyHistory("temperature", MetaConverter.double, maxSize = 4)
            val second = device.propertyHistory("temperature", MetaConverter.double, maxSize = 4)
            val differentSize = device.propertyHistory("temperature", MetaConverter.double, maxSize = 8)
            val differentStart = device.propertyHistory(
                property = "temperature",
                converter = MetaConverter.double,
                maxSize = 4,
                started = SharingStarted.Lazily,
            )

            assertSame(first, second)
            assertNotSame(first, differentSize)
            assertNotSame(first, differentStart)
        } finally {
            device.close()
        }
    }
}
