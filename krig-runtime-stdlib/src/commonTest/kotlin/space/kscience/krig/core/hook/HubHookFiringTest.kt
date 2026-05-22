@file:OptIn(
    space.kscience.krig.core.PerformancePitfall::class,
    space.kscience.krig.core.UnstableKrigForSubclassing::class,
    kotlin.concurrent.atomics.ExperimentalAtomicApi::class,
)

package space.kscience.krig.core.hook

import kotlin.concurrent.atomics.AtomicInt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import space.kscience.krig.api.messages.DeviceDepartureReason
import space.kscience.krig.core.InternalKrigApi
import space.kscience.krig.core.contracts.AbstractDevice
import space.kscience.krig.core.contracts.Device
import space.kscience.krig.core.contracts.DeviceRuntime
import space.kscience.krig.core.runtime.MutableDeviceHub
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName
import kotlin.test.Test
import kotlin.test.assertEquals

private val contextSeq: AtomicInt = AtomicInt(0)
private fun freshContext(prefix: String): Context =
    Context("$prefix-${contextSeq.addAndFetch(1)}")

@OptIn(InternalKrigApi::class, ExperimentalCoroutinesApi::class)
class HubHookFiringTest {

    private class LeafDevice(name: Name, ctx: Context) : AbstractDevice(name, DeviceRuntime(ctx)) {
        override suspend fun readProperty(propertyName: Name) = error("not used")
        override suspend fun writeProperty(propertyName: Name, value: space.kscience.dataforge.meta.Meta) = Unit
        override suspend fun execute(actionName: Name, argument: space.kscience.dataforge.meta.Meta?) = null
    }

    @Test
    fun deviceAttachedHandlerInvokedOnEveryAttach() = runTest {
        val ctx = freshContext("hub-hook")
        val hub = MutableDeviceHub("hub".asName(), ctx)
        val attached = mutableListOf<Pair<Name, Device>>()
        hub.hubHooks.on(DeviceAttached) { name, device -> attached += name to device }

        val devices = listOf("a", "b", "c").map { n ->
            LeafDevice(n.asName(), freshContext(n))
        }
        devices.forEach { hub.attach(it.name, it) }
        advanceUntilIdle()

        assertEquals(3, attached.size)
        assertEquals(devices.map { it.name }, attached.map { it.first })
    }

    @Test
    fun deviceDetachedHandlerReceivesVictimAndReason() = runTest {
        val ctx = freshContext("hub-detach")
        val hub = MutableDeviceHub("hub".asName(), ctx)
        val detached = mutableListOf<Name>()
        hub.hubHooks.on(DeviceDetached) { name, _ -> detached += name }

        val leaf = LeafDevice("a".asName(), freshContext("leaf"))
        hub.attach(leaf.name, leaf)
        hub.detach(leaf.name, DeviceDepartureReason.Graceful).let { }
        advanceUntilIdle()

        assertEquals(listOf(leaf.name), detached)
    }

    @Test
    fun slowAttachedHookDoesNotBlockAttachCommit() = runTest {
        val hub = MutableDeviceHub("hub".asName(), freshContext("hub-slow-hook"))
        val hookStarted = CompletableDeferred<Unit>()
        val releaseHook = CompletableDeferred<Unit>()
        hub.hubHooks.on(DeviceAttached) { _, _ ->
            hookStarted.complete(Unit)
            releaseHook.await()
        }
        val leaf = LeafDevice("a".asName(), freshContext("leaf"))

        hub.attach(leaf.name, leaf)

        assertEquals(Unit, hookStarted.getCompleted())
        assertEquals(mapOf(leaf.name to leaf), hub.devices)

        releaseHook.complete(Unit)
        hub.shutdown()
    }
}
