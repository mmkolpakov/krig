@file:OptIn(
    space.kscience.krig.core.ExperimentalKrigApi::class,
    space.kscience.krig.core.PerformancePitfall::class,
    space.kscience.krig.core.UnstableKrigForSubclassing::class,
)

package space.kscience.krig.demo

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName
import space.kscience.krig.api.hub.HubEvent
import space.kscience.krig.api.messages.DeviceDepartureReason
import space.kscience.krig.core.contracts.AbstractDevice
import space.kscience.krig.core.contracts.DeviceRuntime
import space.kscience.krig.core.runtime.MutableCompositeDevice
import space.kscience.krig.core.runtime.reconcile

//TO-DO - broken now - children after reconcile: [b]
/**
 * Dynamic hub walkthrough: attach, detach, reconcile, hub events.
 *
 * Run: `./gradlew :krig-demo:jvmRun`
 */
public suspend fun dynamicHubDemo(): Unit = coroutineScope {
    val hubCtx = Context("hub-demo")
    val hub = MutableCompositeDevice("hub".asName(), hubCtx)

    println("=== 1. Attach children ===")

    val childA = trackingDevice("a", hubCtx)
    val childB = trackingDevice("b", hubCtx)

    val attached = mutableListOf<HubEvent.Attached>()
    val collector = launch {
        hub.hubEvents.filterIsInstance<HubEvent.Attached>()
            .take(2).toList(attached)
    }
    yield()

    hub.attach("a".asName(), childA)
    hub.attach("b".asName(), childB)

    collector.join()
    println("  attached: ${attached.map { it.name }}")
    println("  children: ${hub.children.keys}")

    println("\n=== 2. Detach child ===")

    hub.detach("a".asName(), DeviceDepartureReason.Evicted).let { }
    println("  children after detach: ${hub.children.keys}")
    println("  detached child remains caller-owned: ${!childA.closed}")

    println("\n=== 3. Reconcile loop ===")

    val desired = MutableStateFlow(setOf("c".asName(), "d".asName()))
    val loop = hub.reconcile(
        context = hubCtx,
        desired = desired,
        produce = { name -> trackingDevice(name.toString(), hubCtx) },
        scope = this,
    )

    // Let reconcile process one cycle, then change desired
    yield()
    desired.value = setOf("c".asName())
    yield()

    println("  children after reconcile: ${hub.children.keys}")
    loop.job.cancel()

    hub.close()
    hubCtx.close()
    println("\nDone - dynamic hub demo complete.")
}

private class TrackingDevice(name: Name, context: Context) :
    AbstractDevice(name, DeviceRuntime(context)) {
    var closed: Boolean = false
        private set
    override fun close() { closed = true; super.close() }
    override suspend fun readProperty(propertyName: Name) = error("not used")
    override suspend fun writeProperty(propertyName: Name, value: Meta) = Unit
    override suspend fun execute(actionName: Name, argument: Meta?) = null
}

private fun trackingDevice(name: String, parentCtx: Context) =
    TrackingDevice(name.asName(), Context("${parentCtx.name}.$name"))
