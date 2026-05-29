@file:OptIn(
    space.kscience.krig.core.PerformancePitfall::class,
    space.kscience.krig.core.UnstableKrigForSubclassing::class,
)

package space.kscience.krig.core.runtime

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import space.kscience.krig.api.messages.DeviceDepartureReason
import space.kscience.krig.api.hub.HubConflictException
import space.kscience.krig.api.hub.HubEvent
import space.kscience.krig.api.result.OperationOutcome
import space.kscience.krig.api.result.runCatchingOperation
import space.kscience.krig.core.InternalKrigApi
import space.kscience.krig.core.contracts.AbstractDevice
import space.kscience.krig.core.contracts.DeviceRuntime
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(InternalKrigApi::class, ExperimentalCoroutinesApi::class, ExperimentalAtomicApi::class)
class DeviceHubE2ETest {

    private class TrackingDevice(name: Name, runtime: DeviceRuntime) : AbstractDevice(name, runtime) {
        var closed: Boolean = false
            private set

        override fun close() {
            closed = true
            super.close()
        }

        override suspend fun doReadPropertyOutcome(
            propertyName: Name,
        ): OperationOutcome<space.kscience.dataforge.meta.Meta> =
            runCatchingOperation { error("not used") }

        override suspend fun doWritePropertyOutcome(
            propertyName: Name,
            value: space.kscience.dataforge.meta.Meta,
        ): OperationOutcome<Unit> = OperationOutcome.OkUnit

        override suspend fun doExecuteOutcome(
            actionName: Name,
            argument: space.kscience.dataforge.meta.Meta?,
        ): OperationOutcome<space.kscience.dataforge.meta.Meta?> = OperationOutcome.Ok(null)
    }

    /**
     * DataForge [Context] names are globally unique per JVM; parallel/back-to-back tests with
     * `Context("same-name")` throw. A per-test suffix keeps each run independent.
     */
    private val contextCounter: AtomicInt = AtomicInt(0)

    private fun freshContext(label: String): Context =
        Context("$label-${contextCounter.addAndFetch(1)}")

    private fun trackingDevice(nameStr: String, parentContext: Context): TrackingDevice {
        val name = nameStr.asName()
        return TrackingDevice(
            name,
            DeviceRuntime(Context("${parentContext.name}.$nameStr")),
        )
    }

    @Test
    fun concurrentAttachesEmit3HubEvents() = runTest {
        val hubCtx = freshContext("concurrent")
        val hub = MutableDeviceHub("hub".asName(), hubCtx)
        val collected = mutableListOf<HubEvent>()
        val collector = launch {
            hub.hubEvents.filterIsInstance<HubEvent.Attached>().take(3).toList(collected)
        }
        val items = listOf("a", "b", "c").map { it.asName() to trackingDevice(it, hubCtx) }
        supervisorScope {
            items.map { (n, d) -> async { hub.attach(n, d) } }.awaitAll()
        }
        advanceUntilIdle()
        collector.join()
        assertEquals(3, collected.size)
        assertEquals(setOf("a", "b", "c").map { it.asName() }.toSet(), hub.devices.keys)
    }

    @Test
    fun reconcileEvictsAbsentChildrenWithEvictedReason() = runTest {
        val hubCtx = freshContext("reconcile")
        val hub = MutableDeviceHub("hub".asName(), hubCtx)
        listOf("a", "b", "c").forEach { hub.attach(it.asName(), trackingDevice(it, hubCtx)) }

        val detachEvents = mutableListOf<HubEvent.Detached>()
        val collector = launch {
            hub.hubEvents.filterIsInstance<HubEvent.Detached>().take(2).toList(detachEvents)
        }

        val desired = MutableStateFlow(setOf("a".asName()))
        val loop = context(hubCtx) {
            hub.reconcile(
                desired,
                produce = { trackingDevice(it.toString(), hubCtx) },
                scope = this@runTest,
            )
        }
        advanceUntilIdle()
        collector.join()

        assertEquals(setOf("a".asName()), hub.devices.keys)
        assertEquals(2, detachEvents.size)
        assertTrue(detachEvents.all { it.reason == DeviceDepartureReason.Evicted })
        loop.job.cancel()
    }

    @Test
    fun closeCascadesWithParentClosedReason() = runTest {
        val hubCtx = freshContext("cascade")
        val hub = MutableDeviceHub("hub".asName(), hubCtx)
        val devices = listOf("a", "b", "c").map { trackingDevice(it, hubCtx) }
        devices.forEach { hub.attach(it.name, it) }

        val cascadeEvents = mutableListOf<HubEvent.Detached>()
        val collector = launch {
            hub.hubEvents.filterIsInstance<HubEvent.Detached>().take(3).toList(cascadeEvents)
        }
        advanceUntilIdle()

        hub.close()
        advanceUntilIdle()
        collector.join()

        assertTrue(devices.all { it.closed }, "all children must be closed on cascade")
        assertEquals(emptyMap(), hub.devices)
        assertTrue(cascadeEvents.all { it.reason == DeviceDepartureReason.ParentClosed })
        assertEquals(3, cascadeEvents.size)
    }

    @Test
    fun concurrentAttachSameNameProducesOneWinnerRestThrowConflict() = runTest {
        val hubCtx = freshContext("conflict")
        val hub = MutableDeviceHub("hub".asName(), hubCtx)
        val candidates = (0 until 10).map {
            TrackingDevice(
                "a".asName(),
                DeviceRuntime(Context("${hubCtx.name}.a-$it")),
            )
        }
        val conflicts = mutableListOf<Throwable>()

        supervisorScope {
            candidates.map { d ->
                async {
                    runCatching { hub.attach("a".asName(), d) }
                        .exceptionOrNull()
                        ?.let(conflicts::add)
                }
            }.awaitAll()
        }
        advanceUntilIdle()

        assertEquals(1, hub.devices.size)
        assertEquals(9, conflicts.size)
        assertTrue(conflicts.all { it is HubConflictException })
    }
}
