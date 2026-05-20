package space.kscience.krig.api.result

import space.kscience.krig.api.faults.OperationFault
import space.kscience.krig.api.faults.OperationFaultException
import space.kscience.krig.api.faults.GenericOperationFault
import space.kscience.krig.api.faults.OperationFaultTypes
import space.kscience.krig.api.faults.TransportFault
import space.kscience.krig.api.faults.ValidationFault
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.asName
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OperationOutcomeTest {
    private val sampleFault: OperationFault = GenericOperationFault(faultType = "test".asName(), message = "boom")

    @Test
    fun mapTransformsSuccessOnly() {
        val s: OperationOutcome<Int> = OperationOutcome.Ok(2)
        val mapped = s.map { it * 3 }
        assertEquals(OperationOutcome.Ok(6), mapped)
        val f: OperationOutcome<Int> = OperationOutcome.Fail(sampleFault)
        assertEquals(f, f.map { it * 3 })
    }

    @Test
    fun recoverFallsBackOnFailure() {
        val s: OperationOutcome<Int> = OperationOutcome.Ok(2)
        val f: OperationOutcome<Int> = OperationOutcome.Fail(sampleFault)
        assertEquals(2, s.recover { 99 })
        assertEquals(99, f.recover { 99 })
    }

    @Test
    fun getOrNullReturnsNullOnFailure() {
        val s: OperationOutcome<Int> = OperationOutcome.Ok(2)
        val f: OperationOutcome<Int> = OperationOutcome.Fail(sampleFault)
        assertEquals(2, s.getOrNull())
        assertNull(f.getOrNull())
    }

    @Test
    fun getOrThrowRaisesOperationFaultException() {
        val s: OperationOutcome<Int> = OperationOutcome.Ok(2)
        val f: OperationOutcome<Int> = OperationOutcome.Fail(sampleFault)
        assertEquals(2, s.getOrThrow())
        val exn = assertFailsWith<OperationFaultException> { f.getOrThrow() }
        assertEquals(sampleFault, exn.fault)
    }

    @Test
    fun mapFaultTransformsFailureOnly() {
        val original: OperationOutcome<Int> = OperationOutcome.Fail(sampleFault)
        val mapped = original.mapFault {
            GenericOperationFault(faultType = "wrapped".asName(), message = "wrapped: ${it.faultType}")
        }
        assertTrue(mapped is OperationOutcome.Fail)
        assertEquals("wrapped".asName(), mapped.fault.faultType)
    }

    @Test
    fun flatMapChainsSuccessOnly() {
        val s: OperationOutcome<Int> = OperationOutcome.Ok(5)
        val chained = s.flatMap { OperationOutcome.Ok(it.toString()) }
        assertEquals(OperationOutcome.Ok("5"), chained)
        val f: OperationOutcome<Int> = OperationOutcome.Fail(sampleFault)
        val fChained: OperationOutcome<String> = f.flatMap { OperationOutcome.Ok(it.toString()) }
        assertTrue(fChained is OperationOutcome.Fail)
        assertEquals(sampleFault, fChained.fault)
    }

    @Test
    fun runCatchingOperationWrapsOperationFaultException() {
        val ok = runCatchingOperation { 42 }
        assertEquals(OperationOutcome.Ok(42), ok)
        val validation = ValidationFault(details = Meta { "message" put "kaboom" })
        val fail = runCatchingOperation<Int> { throw OperationFaultException(validation) }
        assertTrue(fail is OperationOutcome.Fail)
        assertEquals(OperationFaultTypes.Validation, fail.fault.faultType)
    }

    @Test
    fun runCatchingOperationPropagatesProgrammingBugs() {
        assertFailsWith<IllegalArgumentException> {
            runCatchingOperation<Int> { throw IllegalArgumentException("kaboom") }
        }
        assertFailsWith<IllegalStateException> {
            runCatchingOperation<Int> { error("kaboom") }
        }
    }

    @Test
    fun runCatchingOperationKeepsIoFailureDiagnostics() {
        val fail = runCatchingOperation<Int> { throw IOException("wire down") }
        assertTrue(fail is OperationOutcome.Fail)
        val fault = fail.fault as TransportFault
        assertEquals(OperationFaultTypes.Transport, fault.faultType)
        assertEquals("IOException", fault.causeType)
        assertEquals("wire down", fault.message)
        assertTrue(fault.details.toString().contains("wire down"))
        assertTrue(!fault.details.toString().contains("runCatchingOperationKeepsIoFailureDiagnostics"))
    }
}
