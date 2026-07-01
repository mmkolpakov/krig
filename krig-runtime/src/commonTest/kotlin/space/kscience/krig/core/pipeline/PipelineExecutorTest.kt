@file:OptIn(space.kscience.krig.core.InternalKrigApi::class)

package space.kscience.krig.core.pipeline

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName
import space.kscience.krig.api.descriptors.PropertyDescriptor
import space.kscience.krig.api.descriptors.PropertyKind
import space.kscience.krig.api.descriptors.TypeIds
import space.kscience.krig.api.descriptors.attributes.BehaviorAttribute
import space.kscience.krig.api.descriptors.attributes.OperationAttributeKeys
import space.kscience.krig.api.descriptors.operationAttributes
import space.kscience.krig.api.descriptors.attributes.ResourceLock
import space.kscience.krig.api.descriptors.attributes.RetryPolicy
import space.kscience.krig.api.faults.GenericOperationFault
import space.kscience.krig.api.faults.InvalidStateFault
import space.kscience.krig.api.faults.OperationFault
import space.kscience.krig.api.faults.OperationFaultTypes
import space.kscience.krig.api.faults.TimeoutFault
import space.kscience.krig.api.result.OperationOutcome
import space.kscience.krig.core.contracts.CapabilityToggles
import space.kscience.krig.core.contracts.typed.TypedReader
import space.kscience.krig.core.operations.ResourceLockRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private class PipelineSystemFailure : Throwable("boom")

class PipelineExecutorTest {

    private fun doubleDescriptor(name: String = "value"): PropertyDescriptor = PropertyDescriptor(
        name = name.asName(),
        kind = PropertyKind.LOGICAL,
        valueTypeId = TypeIds.DOUBLE,
    )

    private fun planFor(descriptor: PropertyDescriptor, policy: OperationPolicy = OperationPolicy()): OperationPlan =
        OperationPlan(
            context = OperationContext(OperationKinds.Read, descriptor.name, descriptor),
            policy = policy,
        )

    @Test
    fun executor_readsThroughGatesAndObservers_onSuccess() = runTest {
        var observed = false
        var observerFault: OperationFault? = null
        val reader = TypedReader { 42.0 }
        val execute = compileOperationExecutor(
            gates = listOf(OperationGate { OperationOutcome.OkUnit }),
            observers = listOf(OperationObserver { _, _, fault -> observed = true; observerFault = fault }),
            registry = ResourceLockRegistry(),
        )

        val result = execute(planFor(doubleDescriptor()), Unit) { OperationOutcome.Ok(reader.read()) }

        assertEquals(42.0, assertIs<OperationOutcome.Ok<Double>>(result).value)
        assertTrue(observed, "observer must run on success")
        assertEquals(null, observerFault, "observer must see null fault on success")
    }

    @Test
    fun executor_propagatesGateDenial_andObserverSeesFault() = runTest {
        var observerFault: OperationFault? = null
        val execute = compileOperationExecutor(
            gates = listOf(OperationGate {
                OperationOutcome.Fail(
                    InvalidStateFault(
                        currentState = "Detached",
                        requiredState = "Running",
                        operation = "test",
                    ),
                )
            }),
            observers = listOf(OperationObserver { _, _, fault -> observerFault = fault }),
            registry = ResourceLockRegistry(),
        )

        val result = execute(planFor(doubleDescriptor()), Unit) {
            error("reader must not be called when gate denies")
        }

        assertTrue(result is OperationOutcome.Fail)
        assertTrue(result.fault is InvalidStateFault)
        assertTrue(observerFault is InvalidStateFault, "observer must see the gate's fault")
    }

    @Test
    fun executor_observerExceptionsDoNotAffectCaller() = runTest {
        val execute = compileOperationExecutor(
            gates = emptyList(),
            observers = listOf(OperationObserver { _, _, _ -> error("observer failure must be swallowed") }),
            registry = ResourceLockRegistry(),
        )

        val result = execute(planFor(doubleDescriptor()), Unit) { OperationOutcome.Ok(1.0) }

        assertEquals(1.0, assertIs<OperationOutcome.Ok<Double>>(result).value)
    }

    @Test
    fun duplicateResourceLocksAreMergedBeforeAcquisition() = runTest {
        val resource = "bus".asName()
        val locks = listOf(
            ResourceLock(resource),
            ResourceLock(resource),
        )

        val result = withTimeout(1.seconds) {
            acquireAllLocks(ResourceLockRegistry(), locks) { 42 }
        }

        assertEquals(42, result)
    }

    @Test
    fun nestedAcquisitionOfHeldResourceDoesNotDeadlock() = runTest {
        val registry = ResourceLockRegistry()
        val bus = listOf(ResourceLock("bus".asName()))

        // Re-entering the same resource the caller already holds must not deadlock the non-reentrant
        // mutex: the inner acquisition is a no-op skip (same coroutine = sequential access).
        val result = withTimeout(1.seconds) {
            acquireAllLocks(registry, bus) {
                acquireAllLocks(registry, bus) { 7 }
            }
        }

        assertEquals(7, result)
    }

    @Test
    fun resourceArbitrationRejectsBeforeLockAcquisition() = runTest {
        val lock = ResourceLock("bus".asName())
        val descriptor = doubleDescriptor()
        val fault = InvalidStateFault(operation = "arbitration-test")
        var seenRequest: ResourceArbitrationRequest? = null
        var terminalCalled = false
        val execute = compileOperationExecutor(
            gates = emptyList(),
            observers = emptyList(),
            registry = ResourceLockRegistry(),
        )
        val plan = OperationPlan(
            context = OperationContext(OperationKinds.Read, descriptor.name, descriptor),
            policy = OperationPolicy(
                locks = listOf(lock),
                resourceArbitration = ResourceArbitrationPolicy { request ->
                    seenRequest = request
                    ResourceArbitrationDecision.Reject(fault)
                },
            ),
        )

        val result = execute(plan, Unit) {
            terminalCalled = true
            OperationOutcome.Ok(1.0)
        }

        val failure = assertIs<OperationOutcome.Fail>(result)
        assertEquals(fault, failure.fault)
        assertEquals(listOf(lock), seenRequest?.locks)
        assertEquals(emptySet(), seenRequest?.heldLocks)
        assertEquals(false, terminalCalled)
    }

    @Test
    fun preemptionDecisionFailsClosedWithoutPreemptiveExecutor() = runTest {
        val lock = ResourceLock("bus".asName())
        val descriptor = doubleDescriptor()
        var terminalCalled = false
        val execute = compileOperationExecutor(
            gates = emptyList(),
            observers = emptyList(),
            registry = ResourceLockRegistry(),
        )
        val plan = OperationPlan(
            context = OperationContext(OperationKinds.Write, descriptor.name, descriptor),
            policy = OperationPolicy(
                locks = listOf(lock),
                resourceArbitration = ResourceArbitrationPolicy {
                    ResourceArbitrationDecision.Preempt(
                        ResourcePreemptionPlan(
                            resources = setOf(lock.resourceName),
                            reason = "emergency stop",
                        ),
                    )
                },
            ),
        )

        val result = execute(plan, Unit) {
            terminalCalled = true
            OperationOutcome.Ok(1.0)
        }

        val failure = assertIs<OperationOutcome.Fail>(result)
        val fault = assertIs<GenericOperationFault>(failure.fault)
        assertEquals(OperationFaultTypes.InvalidState, fault.faultType)
        assertEquals(false, terminalCalled)
    }

    @Test
    fun retryDoesNotRetryNonRecoverableFaults() = runTest {
        var attempts = 0
        val result = withIoRetry(RetryPolicy(maxAttempts = 1_000_000, initialDelay = 10.milliseconds)) {
            attempts++
            OperationOutcome.Fail(InvalidStateFault(operation = "retry-test"))
        }

        assertEquals(1, attempts)
        assertTrue(result is OperationOutcome.Fail)
        assertTrue(result.fault is InvalidStateFault)
    }

    @Test
    fun retryDelaysBetweenRecoverableFaults() = runTest {
        var attempts = 0
        val job = async(start = CoroutineStart.UNDISPATCHED) {
            withIoRetry(RetryPolicy(maxAttempts = 1_000_000, initialDelay = 10.milliseconds)) {
                attempts++
                OperationOutcome.Fail(TimeoutFault())
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
            gates = emptyList(),
            observers = listOf(
                OperationObserver { _, durationNanos, _ -> observedNanos = durationNanos },
            ),
            registry = ResourceLockRegistry(),
            timeSource = scheduler.timeSource,
        )
        val descriptor = PropertyDescriptor(
            name = "value".asName(),
            kind = PropertyKind.LOGICAL,
            valueTypeId = TypeIds.DOUBLE,
        )
        val plan = OperationPlan(
            context = OperationContext(OperationKinds.Read, descriptor.name, descriptor),
            policy = OperationPolicy(),
        )
        val result = execute(plan, Unit) { _ ->
            scheduler.advanceTimeBy(1.seconds)
            OperationOutcome.Ok(1.0)
        }

        assertEquals(1.0, assertIs<OperationOutcome.Ok<Double>>(result).value)
        assertEquals(1.seconds.inWholeNanoseconds, observedNanos)
    }

    @Test
    fun operationExecutorReusesPlanWithDifferentPayloads() = runTest {
        val seenByGate = mutableListOf<Name>()
        val seenByObserver = mutableListOf<Name>()
        val descriptor = PropertyDescriptor(
            name = "setpoint".asName(),
            kind = PropertyKind.LOGICAL,
            valueTypeId = TypeIds.DOUBLE,
        )
        val context = OperationContext(OperationKinds.Write, descriptor.name, descriptor, "device".asName())
        val execute = compileOperationExecutor(
            gates = listOf(OperationGate { gateContext ->
                seenByGate += gateContext.name
                OperationOutcome.OkUnit
            }),
            observers = listOf(OperationObserver { observerContext, _, _ ->
                seenByObserver += observerContext.name
            }),
            registry = ResourceLockRegistry(),
        )
        val plan = OperationPlan(context, OperationPolicy())
        val terminal: OperationTerminal = { payload ->
            OperationOutcome.Ok(payload as Double + 1.0)
        }

        assertEquals(2.0, assertIs<OperationOutcome.Ok<Double>>(execute(plan, 1.0, terminal)).value)
        assertEquals(3.0, assertIs<OperationOutcome.Ok<Double>>(execute(plan, 2.0, terminal)).value)
        assertEquals(listOf(descriptor.name, descriptor.name), seenByGate)
        assertEquals(listOf(descriptor.name, descriptor.name), seenByObserver)
    }

    @Test
    fun observerSeesSystemFaultForThrowable() = runTest {
        var observerFault: OperationFault? = null
        val execute = compileOperationExecutor(
            gates = emptyList(),
            observers = listOf(OperationObserver { _, _, fault -> observerFault = fault }),
            registry = ResourceLockRegistry(),
        )

        assertFailsWith<PipelineSystemFailure> {
            execute(planFor(doubleDescriptor()), Unit) { throw PipelineSystemFailure() }
        }

        assertTrue(observerFault is GenericOperationFault, "observer must see system failures")
        assertEquals(OperationFaultTypes.FatalSystem, (observerFault as GenericOperationFault).faultType)
    }

    @Test
    fun globalTimeoutCoversGates() = runTest {
        val execute = compileOperationExecutor(
            gates = listOf(OperationGate {
                delay(2.seconds)
                OperationOutcome.OkUnit
            }),
            observers = emptyList(),
            registry = ResourceLockRegistry(),
        )
        val plan = planFor(doubleDescriptor(), OperationPolicy(timeout = 1.seconds))

        val result = execute(plan, Unit) { OperationOutcome.Ok(1.0) }

        assertTrue(result is OperationOutcome.Fail)
        assertTrue(result.fault is TimeoutFault)
    }

    @Test
    fun ioRetryDoesNotRetryGateFault() = runTest {
        var gateCalls = 0
        var terminalCalls = 0
        val execute = compileOperationExecutor(
            gates = listOf(OperationGate {
                gateCalls++
                OperationOutcome.Fail(InvalidStateFault(operation = "gate-test"))
            }),
            observers = emptyList(),
            registry = ResourceLockRegistry(),
        )
        val plan = planFor(doubleDescriptor(), OperationPolicy(retry = RetryPolicy(maxAttempts = 10)))

        val result = execute(plan, Unit) {
            terminalCalls++
            OperationOutcome.Ok(1.0)
        }

        assertTrue(result is OperationOutcome.Fail)
        assertTrue(result.fault is InvalidStateFault)
        assertEquals(1, gateCalls)
        assertEquals(0, terminalCalls)
    }

    @Test
    fun removingTimeoutInterceptorByKeyDisablesTheTimeoutLayer() = runTest {
        val plan = planFor(doubleDescriptor(), OperationPolicy(timeout = 1.milliseconds))
        val slowTerminal: OperationTerminal = {
            delay(1.seconds)
            OperationOutcome.Ok(1.0)
        }
        val full = defaultOperationInterceptors(emptyList(), ResourceLockRegistry())

        val timedOut = full.compileChain(plan, emptyList(), kotlin.time.TimeSource.Monotonic, slowTerminal)(Unit)
        assertTrue(timedOut is OperationOutcome.Fail && timedOut.fault is TimeoutFault)

        val withoutTimeout = full.without(BuiltinInterceptorKeys.Timeout)
            .compileChain(plan, emptyList(), kotlin.time.TimeSource.Monotonic, slowTerminal)(Unit)
        assertEquals(1.0, assertIs<OperationOutcome.Ok<Double>>(withoutTimeout).value)
    }

    @Test
    fun capabilityGateDeniesSuppressedRequiredCapability() = runTest {
        val capabilityId = "capability.audit".asName()
        val toggles = CapabilityToggles().apply { suppress(capabilityId) }
        val descriptor = PropertyDescriptor(
            name = "value".asName(),
            kind = PropertyKind.LOGICAL,
            valueTypeId = TypeIds.DOUBLE,
            attributes = operationAttributes {
                OperationAttributeKeys.Behavior(BehaviorAttribute(requiredCapabilityIds = setOf(capabilityId)))
            },
        )
        val context = OperationContext(OperationKinds.Read, descriptor.name, descriptor, "device".asName())

        val result = CapabilityGate("device", toggles).check(context)

        val failure = assertIs<OperationOutcome.Fail>(result)
        assertIs<InvalidStateFault>(failure.fault)
    }
}
