package space.kscience.krig.core.pipeline

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.TestCoroutineScheduler
import space.kscience.krig.api.faults.DeviceFault
import space.kscience.krig.api.faults.GenericDeviceFault
import space.kscience.krig.api.faults.InvalidStateFault
import space.kscience.krig.api.faults.TimeoutFault
import space.kscience.krig.api.result.DeviceOutcome
import space.kscience.krig.api.spec.RetryPolicy
import space.kscience.krig.api.spec.ResourceLockSpec
import space.kscience.krig.core.contracts.typed.GenericTypedReader
import space.kscience.krig.core.operations.ResourceLockRegistry
import space.kscience.dataforge.names.asName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private class PipelineSystemFailure : Throwable("boom")

class PipelineExecutorTest {

    @Test
    fun pipeline_readsThroughGatesAndObservers_onSuccess() = runTest {
        var observed = false
        var observerFault: DeviceFault? = null
        val reader = GenericTypedReader { 42.0 }

        val pipeline = Pipeline<Unit, DeviceOutcome<Double>>()
        pipeline.prepend { input, next -> // gate
            next(input)
        }
        pipeline.wrapWithTiming { _, fault -> observed = true; observerFault = fault }
        val execute = pipeline.build { DeviceOutcome.Ok(reader.read()) }

        val result = execute(Unit)
        assertEquals(42.0, assertIs<DeviceOutcome.Ok<Double>>(result).value)
        assertTrue(observed, "observer must run on success")
        assertEquals(null, observerFault, "observer must see null fault on success")
    }

    @Test
    fun pipeline_propagatesGateDenial_andObserverSeesFault() = runTest {
        var observerFault: DeviceFault? = null
        val pipeline = Pipeline<Unit, DeviceOutcome<Double>>()
        pipeline.prepend { _, _ ->
            DeviceOutcome.Fail(
                InvalidStateFault(
                    currentState = "Detached",
                    requiredState = "Running",
                    operation = "test",
                ),
            )
        }
        pipeline.wrapWithTiming { _, fault -> observerFault = fault }
        val execute = pipeline.build { error("reader must not be called when gate denies") }

        val result = execute(Unit)
        assertTrue(result is DeviceOutcome.Fail)
        assertTrue(result.fault is InvalidStateFault)
        assertTrue(observerFault is InvalidStateFault, "observer must see the gate's fault")
    }

    @Test
    fun pipeline_observerExceptionsDoNotAffectCaller() = runTest {
        val pipeline = Pipeline<Unit, DeviceOutcome<Double>>()
        pipeline.wrapWithTiming { _, _ -> try { error("observer failure must be swallowed") } catch (_: Throwable) {} }
        val execute = pipeline.build { DeviceOutcome.Ok(1.0) }
        assertEquals(1.0, assertIs<DeviceOutcome.Ok<Double>>(execute(Unit)).value)
    }

    @Test
    fun duplicateResourceLocksAreMergedBeforeAcquisition() = runTest {
        val resource = "bus".asName()
        val locks = listOf(
            ResourceLockSpec(resource),
            ResourceLockSpec(resource),
        )

        val result = withTimeout(1.seconds) {
            acquireAllLocks(ResourceLockRegistry(), locks) { 42 }
        }

        assertEquals(42, result)
    }

    @Test
    fun retryDoesNotRetryNonRecoverableFaults() = runTest {
        var attempts = 0
        val result = withIoRetry(RetryPolicy(maxAttempts = 1_000_000, initialDelay = 10.milliseconds)) {
            attempts++
            DeviceOutcome.Fail(InvalidStateFault(operation = "retry-test"))
        }

        assertEquals(1, attempts)
        assertTrue(result is DeviceOutcome.Fail)
        assertTrue(result.fault is InvalidStateFault)
    }

    @Test
    fun retryDelaysBetweenRecoverableFaults() = runTest {
        var attempts = 0
        val job = async(start = CoroutineStart.UNDISPATCHED) {
            withIoRetry(RetryPolicy(maxAttempts = 1_000_000, initialDelay = 10.milliseconds)) {
                attempts++
                DeviceOutcome.Fail(TimeoutFault())
            }
        }

        assertTrue(job.isActive, "retry loop must suspend between attempts")
        job.cancelAndJoin()
        assertEquals(1, attempts)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun operationExecutorUsesProvidedTimeSourceForObserverDuration() = runTest {
        val scheduler = TestCoroutineScheduler()
        var observedNanos = -1L
        val execute = compileOperationExecutor(
            timeout = null,
            retry = null,
            gates = emptyList(),
            registry = ResourceLockRegistry(),
            locks = emptyList(),
            timeSource = scheduler.timeSource,
            observers = { durationNanos, _ -> observedNanos = durationNanos },
            terminal = { _: Unit ->
                scheduler.advanceTimeBy(1.seconds)
                DeviceOutcome.Ok(1.0)
            },
        )

        assertEquals(1.0, assertIs<DeviceOutcome.Ok<Double>>(execute(Unit)).value)
        assertEquals(1.seconds.inWholeNanoseconds, observedNanos)
    }

    @Test
    fun observerSeesSystemFaultForThrowable() = runTest {
        var observerFault: DeviceFault? = null
        val pipeline = Pipeline<Unit, DeviceOutcome<Double>>()
        pipeline.wrapWithTiming { _, fault -> observerFault = fault }
        val execute = pipeline.build { throw PipelineSystemFailure() }

        assertFailsWith<PipelineSystemFailure> {
            execute(Unit)
        }

        assertTrue(observerFault is GenericDeviceFault, "observer must see system failures")
        assertEquals("FATAL_SYSTEM_ERROR", (observerFault as GenericDeviceFault).code)
    }

    @Test
    fun globalTimeoutCoversOuterGate() = runTest {
        val pipeline = Pipeline<Unit, DeviceOutcome<Double>>()
        pipeline.prepend { input, next ->
            delay(2.seconds)
            next(input)
        }
        pipeline.wrapWithGlobalTimeout(1.seconds)
        val execute = pipeline.build { DeviceOutcome.Ok(1.0) }

        val result = execute(Unit)

        assertTrue(result is DeviceOutcome.Fail)
        assertTrue(result.fault is TimeoutFault)
    }

    @Test
    fun ioRetryDoesNotRetryOuterGateFault() = runTest {
        var gateCalls = 0
        var terminalCalls = 0
        val pipeline = Pipeline<Unit, DeviceOutcome<Double>>()
        pipeline.wrapWithIoRetry(RetryPolicy(maxAttempts = 10))
        pipeline.prepend { _, _ ->
            gateCalls++
            DeviceOutcome.Fail(InvalidStateFault(operation = "gate-test"))
        }
        val execute = pipeline.build {
            terminalCalls++
            DeviceOutcome.Ok(1.0)
        }

        val result = execute(Unit)

        assertTrue(result is DeviceOutcome.Fail)
        assertTrue(result.fault is InvalidStateFault)
        assertEquals(1, gateCalls)
        assertEquals(0, terminalCalls)
    }
}
