@file:OptIn(
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
    space.kscience.krig.core.InternalKrigApi::class,
    space.kscience.krig.core.KrigPerformancePitfall::class,
    space.kscience.krig.core.UnstableKrigForSubclassing::class,
)

package space.kscience.krig.core.contracts

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName
import space.kscience.krig.api.faults.GenericOperationFault
import space.kscience.krig.api.faults.InvalidStateFault
import space.kscience.krig.api.faults.OperationFaultException
import space.kscience.krig.api.faults.OperationFaultTypes
import space.kscience.krig.api.messages.DeviceAttachedMessage
import space.kscience.krig.api.messages.FaultMessage
import space.kscience.krig.api.result.OperationOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.time.Instant

private val faultFlowContextSeq = atomic(0)

private class FaultEmittingDevice : AbstractDevice(
    name = "fault-source".asName(),
    runtime = DeviceRuntime(Context("fault-flow-${faultFlowContextSeq.incrementAndGet()}")),
) {
    suspend fun pushAttached() = emit(
        DeviceAttachedMessage(
            time = Instant.fromEpochMilliseconds(1),
            deviceName = "child".asName(),
            manifestId = "child.manifest".asName(),
            sourceDevice = name,
        ),
    )

    suspend fun pushFault(fault: FaultMessage) = emit(fault)

    override suspend fun doReadPropertyOutcome(propertyName: Name): OperationOutcome<Meta> =
        OperationOutcome.Ok(Meta.EMPTY)

    override suspend fun doWritePropertyOutcome(propertyName: Name, value: Meta): OperationOutcome<Unit> =
        OperationOutcome.OkUnit

    override suspend fun doExecuteOutcome(actionName: Name, argument: Meta?): OperationOutcome<Meta?> =
        OperationOutcome.Ok(null)
}

class FaultMessageFlowTest {

    @Test
    fun faultMessageFlowEmitsOnlyFaults() = runTest {
        val device = FaultEmittingDevice()
        val fault = FaultMessage(
            time = Instant.fromEpochMilliseconds(2),
            fault = GenericOperationFault(
                faultType = OperationFaultTypes.UnsupportedValue,
                message = "boom",
            ),
            sourceDevice = device.name,
        )

        // Subscribe before emitting: a non-fault control message must be filtered out, the fault must pass.
        val collected = async(start = CoroutineStart.UNDISPATCHED) {
            device.faultMessageFlow.take(1).toList()
        }
        device.pushAttached()
        device.pushFault(fault)

        val result = collected.await()
        assertEquals(1, result.size)
        assertSame(fault, result.single())
    }

    /**
     * After shutdown the plane mailboxes are closed: a control-plane emit must fail with a
     * predictable [InvalidStateFault] instead of suspending forever on a dead channel.
     */
    @Test
    fun emitAfterShutdownFailsPredictably() = runTest {
        val device = FaultEmittingDevice()
        device.shutdown()

        val failure = assertFailsWith<OperationFaultException> {
            device.pushAttached()
        }

        assertIs<InvalidStateFault>(failure.fault)
    }
}
