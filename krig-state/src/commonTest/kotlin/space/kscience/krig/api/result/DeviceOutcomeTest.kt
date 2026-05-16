package space.kscience.krig.api.result

import space.kscience.krig.api.faults.DeviceFault
import space.kscience.krig.api.faults.DeviceFaultException
import space.kscience.krig.api.faults.GenericDeviceFault
import space.kscience.krig.api.faults.ValidationFault
import space.kscience.dataforge.meta.Meta
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceOutcomeTest {
    private val sampleFault: DeviceFault = GenericDeviceFault(code = "TEST", message = "boom")

    @Test
    fun mapTransformsSuccessOnly() {
        val s: DeviceOutcome<Int> = DeviceOutcome.Ok(2)
        val mapped = s.map { it * 3 }
        assertEquals(DeviceOutcome.Ok(6), mapped)
        val f: DeviceOutcome<Int> = DeviceOutcome.Fail(sampleFault)
        assertEquals(f, f.map { it * 3 })
    }

    @Test
    fun recoverFallsBackOnFailure() {
        val s: DeviceOutcome<Int> = DeviceOutcome.Ok(2)
        val f: DeviceOutcome<Int> = DeviceOutcome.Fail(sampleFault)
        assertEquals(2, s.recover { 99 })
        assertEquals(99, f.recover { 99 })
    }

    @Test
    fun getOrNullReturnsNullOnFailure() {
        val s: DeviceOutcome<Int> = DeviceOutcome.Ok(2)
        val f: DeviceOutcome<Int> = DeviceOutcome.Fail(sampleFault)
        assertEquals(2, s.getOrNull())
        assertNull(f.getOrNull())
    }

    @Test
    fun getOrThrowRaisesDeviceFaultException() {
        val s: DeviceOutcome<Int> = DeviceOutcome.Ok(2)
        val f: DeviceOutcome<Int> = DeviceOutcome.Fail(sampleFault)
        assertEquals(2, s.getOrThrow())
        val exn = assertFailsWith<DeviceFaultException> { f.getOrThrow() }
        assertEquals(sampleFault, exn.fault)
    }

    @Test
    fun mapFaultTransformsFailureOnly() {
        val original: DeviceOutcome<Int> = DeviceOutcome.Fail(sampleFault)
        val mapped = original.mapFault {
            GenericDeviceFault(code = "WRAPPED", message = "wrapped: ${it.code}")
        }
        assertTrue(mapped is DeviceOutcome.Fail)
        assertEquals("WRAPPED", mapped.fault.code)
    }

    @Test
    fun flatMapChainsSuccessOnly() {
        val s: DeviceOutcome<Int> = DeviceOutcome.Ok(5)
        val chained = s.flatMap { DeviceOutcome.Ok(it.toString()) }
        assertEquals(DeviceOutcome.Ok("5"), chained)
        val f: DeviceOutcome<Int> = DeviceOutcome.Fail(sampleFault)
        val fChained = f.flatMap<Int, String> { DeviceOutcome.Ok(it.toString()) }
        assertTrue(fChained is DeviceOutcome.Fail)
        assertEquals(sampleFault, fChained.fault)
    }

    @Test
    fun runCatchingDeviceWrapsDeviceFaultException() {
        val ok = runCatchingDevice { 42 }
        assertEquals(DeviceOutcome.Ok(42), ok)
        val validation = ValidationFault(details = Meta { "message" put "kaboom" })
        val fail = runCatchingDevice<Int> { throw DeviceFaultException(validation) }
        assertTrue(fail is DeviceOutcome.Fail)
        assertEquals("VALIDATION_ERROR", fail.fault.code)
    }

    @Test
    fun runCatchingDevicePropagatesProgrammingBugs() {
        assertFailsWith<IllegalArgumentException> {
            runCatchingDevice<Int> { throw IllegalArgumentException("kaboom") }
        }
        assertFailsWith<IllegalStateException> {
            runCatchingDevice<Int> { error("kaboom") }
        }
    }

    @Test
    fun runCatchingDeviceKeepsIoFailureDiagnostics() {
        val fail = runCatchingDevice<Int> { throw IOException("wire down") }
        assertTrue(fail is DeviceOutcome.Fail)
        val fault = fail.fault as GenericDeviceFault
        assertEquals("IOException", fault.code)
        assertEquals("wire down", fault.message)
        assertTrue(fault.details.toString().contains("wire down"))
        assertTrue(!fault.details.toString().contains("runCatchingDeviceKeepsIoFailureDiagnostics"))
    }
}
