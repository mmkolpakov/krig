package space.kscience.controls.composite.dsl

import space.kscience.controls.api.addressing.Address
import space.kscience.controls.api.data.Quality
import space.kscience.controls.api.data.StateValue
import space.kscience.controls.api.addressing.toAddress
import space.kscience.controls.api.addressing.toAddressOrNull
import space.kscience.controls.api.data.DataQuality
import space.kscience.dataforge.names.asName
import space.kscience.dataforge.names.parseAsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/**
 * Tests for non-serialization logic within the `controls-composite-old` classes.
 */
//all passed
class ModelLogicTest {

    /**
     * Verifies the parsing logic of the [Address] class for both valid and invalid formats.
     */
    @Test
    fun testAddressParsing() {
        val validStr = "myHub::myDevice.group[0]"
        val address = Address.parse(validStr)
        assertEquals("myHub".asName(), address.route)
        assertEquals("myDevice.group[0]".parseAsName(), address.device)
        assertEquals("myHub::myDevice.group[0]", address.toString())

        assertEquals(address, validStr.toAddress())

        assertFailsWith<IllegalArgumentException> { Address.parse("invalid-string") }
        assertFailsWith<IllegalArgumentException> { "invalid-string".toAddress() }
        assertNull(Address.parseOrNull("invalid-string"))
        assertNull("invalid-string".toAddressOrNull())
    }

    /**
     * Verifies that child addresses are correctly resolved from a parent address.
     */
    @Test
    fun testAddressChildResolution() {
        val parentAddress = Address("myHub".asName(), "parent".asName())
        val childAddress = parentAddress.resolveChild("child[a]".asName())
        assertEquals("myHub".asName(), childAddress.route)
        assertEquals("parent.child\\[a\\]", childAddress.device.toString())
    }

    /**
     * Verifies the combination logic of [StateValue], ensuring timestamp and quality are propagated correctly.
     */
    @Test
    fun testStateValueCombination() {
        val clock = Clock.System
        val time1 = clock.now()
        val time2 = time1 + 10.seconds

        val state1 = StateValue("value1", time1, DataQuality.OK)
        val state2 = StateValue(123, time2, DataQuality.UNKNOWN)

        val combined = StateValue.combine(state1, state2) { v1, v2 -> "$v1:$v2" }

        assertEquals("value1:123", combined.value)
        assertEquals(time2, combined.timestamp, "Timestamp should be the maximum of the two.")
        assertEquals(Quality.UNKNOWN, combined.quality.quality, "Quality should be the 'worst' of the two.")

        val errorState = StateValue(456, time1, DataQuality.BAD)
        val combinedWithError = StateValue.combine(state1, errorState) { v1, v2 -> "$v1:$v2" }
        assertEquals(Quality.BAD, combinedWithError.quality.quality, "ERROR quality should have the highest priority.")
    }
}