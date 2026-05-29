@file:OptIn(
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
    space.kscience.krig.core.InternalKrigApi::class,
    space.kscience.krig.core.PerformancePitfall::class,
    space.kscience.krig.core.UnstableKrigForSubclassing::class,
)

package space.kscience.krig.core.runtime

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName
import space.kscience.krig.api.faults.OperationFaultException
import space.kscience.krig.api.result.OperationOutcome
import space.kscience.krig.core.contracts.AbstractDevice
import space.kscience.krig.core.contracts.DeviceRuntime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val groupDrainContextSeq = atomic(0)

private open class GroupDrainTestDevice : AbstractDevice(
    name = "drain".asName(),
    runtime = DeviceRuntime(Context("group-close-gracefully-test-${groupDrainContextSeq.incrementAndGet()}")),
) {
    var shutdownCalls: Int = 0

    override suspend fun doReadPropertyOutcome(propertyName: Name): OperationOutcome<Meta> =
        OperationOutcome.Ok(Meta.EMPTY)

    override suspend fun doWritePropertyOutcome(propertyName: Name, value: Meta): OperationOutcome<Unit> =
        OperationOutcome.OkUnit

    override suspend fun doExecuteOutcome(actionName: Name, argument: Meta?): OperationOutcome<Meta?> =
        OperationOutcome.Ok(null)

    override suspend fun shutdown() {
        shutdownCalls++
    }
}

class DeviceGroupCloseGracefullyTest {
    @Test
    fun closeGracefullyDrainsChildrenBeforeShutdown() = runTest {
        val child = GroupDrainTestDevice()
        val parent = DeviceGroup(
            name = "parent".asName(),
            context = Context("group-graceful-close-test"),
            children = mapOf("child".asName() to child),
        )
        child.enterOperation()

        val closeJob = launch { parent.closeGracefully(1.seconds) }
        runCurrent()

        assertTrue(closeJob.isActive)
        assertFailsWith<OperationFaultException> {
            child.enterOperation()
        }

        child.exitOperation()
        closeJob.join()

        assertTrue(closeJob.isCompleted)
        assertEquals(1, child.shutdownCalls)
    }

    @Test
    fun defaultShutdownSuppressesChildCancellationDuringCleanup() = runTest {
        val child = object : GroupDrainTestDevice() {
            override suspend fun shutdown() {
                throw CancellationException("child cancelled")
            }
        }
        val parent = DeviceGroup(
            name = "parent".asName(),
            context = Context("group-shutdown-cancellation-test"),
            children = mapOf("child".asName() to child),
        )

        parent.shutdown()
    }

    @Test
    fun defaultShutdownClosesChildrenConcurrently() = runTest {
        val first = object : GroupDrainTestDevice() {
            override suspend fun shutdown() {
                delay(100.milliseconds)
                shutdownCalls++
            }
        }
        val second = object : GroupDrainTestDevice() {
            override suspend fun shutdown() {
                delay(100.milliseconds)
                shutdownCalls++
            }
        }
        val parent = DeviceGroup(
            name = "parent".asName(),
            context = Context("group-shutdown-concurrent-test"),
            children = mapOf("first".asName() to first, "second".asName() to second),
        )

        parent.shutdown()

        assertEquals(100L, currentTime)
        assertEquals(1, first.shutdownCalls)
        assertEquals(1, second.shutdownCalls)
    }
}
