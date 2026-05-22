@file:OptIn(
    space.kscience.krig.core.ExperimentalKrigApi::class,
    space.kscience.krig.core.PerformancePitfall::class,
    space.kscience.krig.core.UnstableKrigForSubclassing::class,
)

package space.kscience.krig.demo

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName
import space.kscience.krig.api.hub.HubEvent
import space.kscience.krig.api.messages.DeviceDepartureReason
import space.kscience.krig.core.contracts.AbstractDevice
import space.kscience.krig.core.contracts.DeviceRuntime
import space.kscience.krig.core.runtime.awaitChildren
import space.kscience.krig.core.runtime.deviceHub
import space.kscience.krig.core.runtime.reconcile

/**
 * Device hub walkthrough: attach, detach, reconcile, hub events.
 *
 * Run: `./gradlew :krig-demo:jvmRun`
 */
suspend fun deviceHubDemo(): Unit = coroutineScope {
    val hubCtx = Context("hub-demo")
    val hub = deviceHub("hub", hubCtx)

    println("=== 1. Attach devices ===")

    val childA = trackingDevice("a", hubCtx)
    val childB = trackingDevice("b", hubCtx)

    val attached = mutableListOf<HubEvent.Attached>()
    val collector = launch(start = CoroutineStart.UNDISPATCHED) {
        hub.hubEvents.filterIsInstance<HubEvent.Attached>()
            .take(2).toList(attached)
    }

    hub.attach("a".asName(), childA)
    hub.attach("b".asName(), childB)

    collector.join()
    println("  attached: ${attached.map { it.name }}")
    println("  devices: ${hub.devices.keys}")

    println("\n=== 2. Detach device ===")

    hub.detach("a".asName(), DeviceDepartureReason.Evicted)
    println("  devices after detach: ${hub.devices.keys}")
    println("  detached device remains caller-owned: ${!childA.closed}")

    println("\n=== 3. Reconcile loop ===")

    val desired = MutableStateFlow(setOf("c".asName(), "d".asName()))
    val loop = hub.reconcile(
        context = hubCtx,
        desired = desired,
        produce = { name -> trackingDevice(name.toString(), hubCtx) },
        scope = this,
    )

    hub.awaitChildren(setOf("c".asName(), "d".asName()))
    desired.value = setOf("c".asName())
    val reconciled = hub.awaitChildren(setOf("c".asName()))

    println("  devices after reconcile: ${reconciled.keys}")
    loop.job.cancel()

    hub.close()
    hubCtx.close()
    println("\nDone - device hub demo complete.")
}

private class TrackingDevice(name: Name, context: Context) :
    AbstractDevice(name, DeviceRuntime(context)) {
    var closed: Boolean = false
        private set
    override fun close() { closed = true; super.close() }
    override suspend fun readProperty(propertyName: Name): Meta = error("not used: $propertyName")
    override suspend fun writeProperty(propertyName: Name, value: Meta): Unit =
        error("not used: $propertyName = $value")
    override suspend fun execute(actionName: Name, argument: Meta?) =
        error("not used: $actionName($argument)")
}

private fun trackingDevice(name: String, parentCtx: Context) =
    TrackingDevice(name.asName(), Context("${parentCtx.name}.$name"))
