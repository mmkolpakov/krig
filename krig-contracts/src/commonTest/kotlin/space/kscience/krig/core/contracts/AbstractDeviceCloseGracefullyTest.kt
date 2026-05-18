@file:OptIn(
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
    space.kscience.krig.core.InternalKrigApi::class,
    space.kscience.krig.core.PerformancePitfall::class,
    space.kscience.krig.core.UnstableKrigForSubclassing::class,
)

package space.kscience.krig.core.contracts

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import space.kscience.krig.api.faults.DeviceFaultException
import space.kscience.krig.api.lifecycle.LifecycleState
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private open class DrainTestDevice : AbstractDevice(
    name = "drain".asName(),
    runtime = DeviceRuntime(Context("close-gracefully-test-${drainContextSeq.incrementAndGet()}")),
) {
    var shutdownCalls: Int = 0

    override suspend fun readProperty(propertyName: Name): Meta = Meta.EMPTY
    override suspend fun writeProperty(propertyName: Name, value: Meta) = Unit
    override suspend fun execute(actionName: Name, argument: Meta?): Meta? = null

    override suspend fun shutdown() {
        shutdownCalls++
    }
}

private val drainContextSeq = atomic(0)

class AbstractDeviceCloseGracefullyTest {
    @Test
    fun closeGracefullyResumesImmediatelyWhenInflightDrains() = runTest {
        val device = DrainTestDevice()
        device.enterOperation()
        var closedAt: Long? = null

        val closeJob = launch {
            device.closeGracefully(1.seconds)
            closedAt = currentTime
        }
        runCurrent()
        assertTrue(closeJob.isActive)

        device.exitOperation()
        runCurrent()

        assertEquals(0L, closedAt)
        assertEquals(1, device.shutdownCalls)
    }

    @Test
    fun closeGracefullyRejectsNewOperationsWhileDraining() = runTest {
        val device = DrainTestDevice()
        device.enterOperation()

        val closeJob = launch { device.closeGracefully(1.seconds) }
        runCurrent()

        assertFailsWith<DeviceFaultException> {
            device.enterOperation()
        }

        device.exitOperation()
        runCurrent()

        assertTrue(closeJob.isCompleted)
        assertEquals(1, device.shutdownCalls)
    }

    @Test
    fun operationCanExitAfterGracefulCloseTimeout() = runTest {
        val device = DrainTestDevice()
        device.enterOperation()

        device.closeGracefully(Duration.ZERO)
        device.exitOperation()

        assertEquals(1, device.shutdownCalls)
    }

    @Test
    fun compositeCloseGracefullyDrainsChildrenBeforeShutdown() = runTest {
        val child = DrainTestDevice()
        val parent = CompositeDevice(
            name = "parent".asName(),
            context = Context("composite-graceful-close-test"),
            children = mapOf("child".asName() to child),
        )
        child.enterOperation()

        val closeJob = launch { parent.closeGracefully(1.seconds) }
        runCurrent()

        assertTrue(closeJob.isActive)
        assertFailsWith<DeviceFaultException> {
            child.enterOperation()
        }

        child.exitOperation()
        closeJob.join()

        assertTrue(closeJob.isCompleted)
        assertEquals(1, child.shutdownCalls)
    }

    @Test
    fun defaultShutdownSuppressesChildCancellationDuringCleanup() = runTest {
        val child = object : DrainTestDevice() {
            override suspend fun shutdown() {
                throw CancellationException("child cancelled")
            }
        }
        val parent = CompositeDevice(
            name = "parent".asName(),
            context = Context("shutdown-cancellation-test"),
            children = mapOf("child".asName() to child),
        )

        parent.shutdown()
    }

    @Test
    fun defaultShutdownClosesChildrenConcurrently() = runTest {
        val first = object : DrainTestDevice() {
            override suspend fun shutdown() {
                delay(100.milliseconds)
                shutdownCalls++
            }
        }
        val second = object : DrainTestDevice() {
            override suspend fun shutdown() {
                delay(100.milliseconds)
                shutdownCalls++
            }
        }
        val parent = CompositeDevice(
            name = "parent".asName(),
            context = Context("shutdown-concurrent-test"),
            children = mapOf("first".asName() to first, "second".asName() to second),
        )

        parent.shutdown()

        assertEquals(100L, currentTime)
        assertEquals(1, first.shutdownCalls)
        assertEquals(1, second.shutdownCalls)
    }

    @Test
    fun shutdownFromInsideDeviceScopeDoesNotJoinItself() = runTest {
        val deviceJob = SupervisorJob(coroutineContext[Job])
        val scope = CoroutineScope(coroutineContext + deviceJob)
        val completed = CompletableDeferred<Unit>()

        scope.launch {
            cancelDeviceScopeSafely("drain".asName(), scope)
            completed.complete(Unit)
        }
        runCurrent()

        withTimeout(1.seconds) {
            completed.await()
        }
    }

    @Test
    fun defaultOutcomeCancellationDoesNotMarkDeviceFailed() = runTest {
        val started = CompletableDeferred<Unit>()
        val never = CompletableDeferred<Unit>()
        val device = object : DrainTestDevice() {
            override suspend fun readProperty(propertyName: Name): Meta {
                started.complete(Unit)
                never.await()
                return Meta.EMPTY
            }
        }

        val job = launch {
            val outcome = device.readPropertyOutcome("value".asName())
            error("read completed unexpectedly: $outcome")
        }
        started.await()
        job.cancelAndJoin()

        assertTrue(device.lifecycleState !is LifecycleState.Failed)
    }
}
