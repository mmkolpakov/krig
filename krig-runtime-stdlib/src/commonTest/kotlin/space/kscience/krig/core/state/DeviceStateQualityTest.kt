package space.kscience.krig.core.state

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import space.kscience.krig.api.data.DataQuality
import space.kscience.krig.api.data.ObservedValue
import space.kscience.krig.api.data.QualitySeverity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class DeviceStateQualityTest {
    @Test
    fun mapAndCombinePreserveWorstQuality() = runTest {
        val uncertain = DataQuality(QualitySeverity.UNCERTAIN)
        val left = fixedState(ObservedValue(2.0, Instant.fromEpochMilliseconds(1), DataQuality.GOOD))
        val right = fixedState(ObservedValue(3.0, Instant.fromEpochMilliseconds(2), uncertain))

        val mapped = left.map { it?.times(2.0) }
        val combined = left.combine(right) { a, b -> (a ?: 0.0) + (b ?: 0.0) }

        assertEquals(DataQuality.GOOD, mapped.stateValue.quality)
        assertEquals(4.0, mapped.stateValue.value)
        assertEquals(uncertain, combined.stateValue.quality)
        assertEquals(5.0, combined.stateValue.value)
        assertEquals(listOf(uncertain), combined.stateFlow.toList().map { it.quality })
    }

    private fun <T> fixedState(value: ObservedValue<T?>): DeviceState<T> =
        object : DeviceState<T> {
            override val stateValue: ObservedValue<T?> = value
            override val stateFlow: Flow<ObservedValue<T?>> = flowOf(value)
        }
}
