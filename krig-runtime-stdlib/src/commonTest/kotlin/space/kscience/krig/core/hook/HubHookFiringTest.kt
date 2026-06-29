@file:OptIn(
    space.kscience.krig.core.KrigPerformancePitfall::class,
    space.kscience.krig.core.UnstableKrigForSubclassing::class,
)

package space.kscience.krig.core.hook

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import space.kscience.krig.api.hub.HubEvent
import space.kscience.krig.api.messages.DeviceDepartureReason
import space.kscience.krig.api.result.OperationOutcome
import space.kscience.krig.api.result.runCatchingOperation
import space.kscience.krig.core.InternalKrigApi
import space.kscience.krig.core.contracts.AbstractDevice
import space.kscience.krig.core.contracts.DeviceRuntime
import space.kscience.krig.core.freshTestContext
import space.kscience.krig.core.runtime.MutableDeviceHub
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(InternalKrigApi::class, ExperimentalCoroutinesApi::class)
class HubHookFiringTest {

    private class LeafDevice(name: Name, ctx: Context) : AbstractDevice(name, DeviceRuntime(ctx)) {
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

    @Test
    fun hubEventsReportsEveryAttach() = runTest {
        val ctx = freshTestContext("hub-hook")
        val hub = MutableDeviceHub("hub".asName(), ctx)
        val attachedEvents = async(start = CoroutineStart.UNDISPATCHED) {
            hub.hubEvents.filterIsInstance<HubEvent.Attached>().take(3).toList()
        }

        val devices = listOf("a", "b", "c").map { n ->
            LeafDevice(n.asName(), freshTestContext(n))
        }
        devices.forEach { hub.attach(it.name, it) }

        assertEquals(devices.map { it.name }, attachedEvents.await().map { it.name })
    }

    @Test
    fun hubEventsReportsDetachedReason() = runTest {
        val ctx = freshTestContext("hub-detach")
        val hub = MutableDeviceHub("hub".asName(), ctx)

        val leaf = LeafDevice("a".asName(), freshTestContext("leaf"))
        hub.attach(leaf.name, leaf)
        val detachedEvent = async(start = CoroutineStart.UNDISPATCHED) {
            hub.hubEvents.filterIsInstance<HubEvent.Detached>().first()
        }
        hub.detach(leaf.name, DeviceDepartureReason.Graceful).let { }

        assertEquals(leaf.name, detachedEvent.await().name)
        assertEquals(DeviceDepartureReason.Graceful, detachedEvent.await().reason)
    }

    @Test
    fun attachCommitsTopologyAndPublishesEvent() = runTest {
        val hub = MutableDeviceHub("hub".asName(), freshTestContext("hub-slow-hook"))
        val attachedEvent = async(start = CoroutineStart.UNDISPATCHED) {
            hub.hubEvents.filterIsInstance<HubEvent.Attached>().first()
        }
        val leaf = LeafDevice("a".asName(), freshTestContext("leaf"))

        hub.attach(leaf.name, leaf)

        assertEquals(mapOf(leaf.name to leaf), hub.devices)
        assertEquals(leaf.name, attachedEvent.await().name)

        hub.shutdown()
    }
}
