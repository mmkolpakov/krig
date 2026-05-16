@file:OptIn(space.kscience.krig.core.PerformancePitfall::class)

package space.kscience.krig.dsl

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.misc.DFBuilder
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName
import space.kscience.krig.api.data.ObservedValue
import space.kscience.krig.core.contracts.CompositeDevice
import space.kscience.krig.core.contracts.Device
import space.kscience.krig.core.state.DeviceState
import space.kscience.krig.core.state.MutableDeviceState
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds


/**
 * Builder for composing multiple devices into a composite [Device] (Composite Pattern).
 *
 * ```kotlin
 * val group = deviceGroup {
 *     device("motor1", motor1Instance)
 *     device("motor2", motor2Instance)
 * }
 * val hub: Device = group.start("crate", context, scope)
 * ```
 */
@DFBuilder
@KrigDsl
public class DeviceGroupBuilder {
    private val devices = mutableMapOf<Name, Device>()
    private val deferredDevices = mutableMapOf<Name, InlineDeviceBuilder.() -> Unit>()
    private val subGroups = mutableMapOf<Name, DeviceGroupBuilder>()
    private val bindings = mutableListOf<CoroutineScope.() -> Job>()

    /** Register a pre-built device under the given [name]. */
    public fun device(name: String, device: Device) {
        devices[name.asName()] = device
    }

    /**
     * Declare a device using the [InlineDeviceBuilder] DSL.
     * The device is materialized when the group is started via [start].
     *
     * ```kotlin
     * deviceGroup {
     *     device("motor") {
     *         mutableProperty("setpoint", initial = 0.0)
     *         onStep { dt -> … }
     *         install(Caching) { ttl = 500.milliseconds }
     *     }
     * }
     * ```
     *
     * For devices driven by a pre-built `DeviceBackend` (protocol adapters,
     * physics models), pass the already-materialised [Device] via the
     * `device(name, device)` overload of [DeviceGroupBuilder].
     */
    public fun device(name: String, builder: InlineDeviceBuilder.() -> Unit) {
        deferredDevices[name.asName()] = builder
    }

    /**
     * Declare a nested device sub-group (recursive Composite Pattern).
     *
     * The sub-group is materialized when the parent is started via [start].
     *
     * ```kotlin
     * deviceGroup {
     *     device("motor", motorDevice)
     *     deviceGroup("sensors") {
     *         device("temp", tempDevice)
     *         device("pressure", pressureDevice)
     *     }
     * }
     * ```
     */
    public fun deviceGroup(name: String, builder: DeviceGroupBuilder.() -> Unit) {
        subGroups[name.asName()] = DeviceGroupBuilder().apply(builder)
    }

    /** Bind a device property's state flow to a target mutable state. */
    public fun <T> bindProperty(source: DeviceState<T>, target: MutableDeviceState<T>) {
        bindings += {
            source.stateFlow.onEach { observed ->
                val value = observed.value
                if (value != null) target.updateState(observed)
            }.launchIn(this)
        }
    }

    /**
     * Declarative reactive link between two typed device states with a pure [transform].
     * `source` collects and pushes transformed values to `target`. Nozik's `bindState`/
     * `mapState` DX on typed Data Plane states.
     */
    public fun <T, R> link(
        source: DeviceState<T>,
        target: MutableDeviceState<R>,
        transform: (T) -> R,
    ) {
        bindings += {
            source.stateFlow.onEach { observed ->
                val value = observed.value
                if (value != null) target.updateState(ObservedValue(transform(value), observed.time, observed.quality))
            }.launchIn(this)
        }
    }

    /**
     * Declares a cross-device property wiring: on each tick,
     * read [sourceProperty] from [sourceName] device and write the result
     * to [targetProperty] on [targetName] device.
     *
     * Wirings are activated when the group is started via [start].
     * The [tick] duration controls the polling interval. Under a virtual-time
     * scheduler (see [ClockManager.Virtual][space.kscience.krig.simulation.ClockMode.Virtual])
     * this duration is deterministic; with real dispatchers, it's best-effort.
     *
     * @param sourceName The name of the device to read from.
     * @param sourceProperty The property name to read.
     * @param targetName The name of the device to write to.
     * @param targetProperty The property name to write.
     * @param tick Polling interval for the wiring loop. Default: 10ms.
     */

    /**
     * Declares a cross-device property wiring: on each tick, read [source] and write to [target].
     * Slow reads/writes never overlap: the next tick starts after the previous transfer
     * and delay complete.
     */
    public fun wirePull(
        source: WireEndpoint,
        target: WireEndpoint,
        tick: Duration = 10.milliseconds,
    ) {
        wirings += WiringDeclaration(
            source.device.asName(), source.property.asName(),
            target.device.asName(), target.property.asName(),
            tick,
        )
    }

    /** Convenience overload with string parameters. */
    public fun wirePull(
        sourceName: String,
        sourceProperty: String,
        targetName: String,
        targetProperty: String,
        tick: Duration = 10.milliseconds,
    ) {
        wirePull(WireEndpoint(sourceName, sourceProperty), WireEndpoint(targetName, targetProperty), tick)
    }

    private val wirings = mutableListOf<WiringDeclaration>()

    /**
     * Declares a push-based cross-device property wiring. Subscribes to [source]'s
     * property change flow and pushes each new value to [target] immediately,
     * without a polling interval. Wire is active only while [source]'s property
     * changes — idle periods consume no resources.
     *
     * For periodic sampling use [wirePull] instead.
     */
    public fun wirePush(source: WireEndpoint, target: WireEndpoint) {
        pushWirings += PushWiringDeclaration(
            source.device.asName(), source.property.asName(),
            target.device.asName(), target.property.asName(),
        )
    }

    /** Convenience overload with string parameters. */
    public fun wirePush(
        sourceName: String,
        sourceProperty: String,
        targetName: String,
        targetProperty: String,
    ) {
        wirePush(WireEndpoint(sourceName, sourceProperty), WireEndpoint(targetName, targetProperty))
    }

    private val pushWirings = mutableListOf<PushWiringDeclaration>()

    /**
     * Materialises the group into a [CompositeDevice] — a [Device] that carries its own
     * identity alongside a map of [Device.children]. The fractal contract means a composite
     * is an ordinary Device, not a separate hub type.
     *
     * Each deferred child device (declared via `device(name) { ... }`) is built with
     * its own [Name] and a DataForge child [Context] derived from [context]. This
     * preserves addressability, isolates plugin discovery per device, and makes
     * error diagnostics meaningful.
     *
     * @param name The name of the composite device.
     * @param context DataForge context for coroutines and configuration. Each child
     *                receives its own child context built from this one.
     * @param scope CoroutineScope for activating property bindings and wirings.
     */
    public suspend fun start(name: String, context: Context, scope: CoroutineScope): CompositeDevice {
        // Build deferred devices via the public `device(name, context, builder)` entry
        // point — keeps DeviceGroupBuilder above the InlineDeviceBuilder visibility fence.
        for ((devName, builder) in deferredDevices) {
            val childContext = context.buildContext(devName)
            devices[devName] = device(devName, childContext, builder)
        }
        // Recursively materialise nested sub-groups; a sub-group is itself a CompositeDevice
        // (a Device with children), so it fits into the children map naturally.
        for ((subName, subBuilder) in subGroups) {
            val childContext = context.buildContext(subName)
            devices[subName] = subBuilder.start(subName.toString(), childContext, scope)
        }

        // Activate reactive property bindings. Each binding's Job is scoped to the
        // composite's [scope]; cancellation of that scope cancels the binding too, so
        // retaining a handle is unnecessary.
        for (binding in bindings) {
            val scopedJob = binding(scope)
        }
        val composite = CompositeDevice(name.asName(), context, devices.toMap())

        // Activate cross-device pull wirings as periodic control loops. Each loop is
        // sequential: a slow read/write naturally delays the next poll, so values never overlap.
        for (wiring in wirings) {
            val sourceDevice = devices[wiring.sourceName]
                ?: error("wirePull source device '${wiring.sourceName}' not found in group")
            val targetDevice = devices[wiring.targetName]
                ?: error("wirePull target device '${wiring.targetName}' not found in group")

            scope.launch {
                while (isActive) {
                    val value = sourceDevice.readProperty(wiring.sourceProperty)
                    targetDevice.writeProperty(wiring.targetProperty, value)
                    delay(wiring.tick)
                }
            }
        }

        // Activate push wirings — subscribe to property change flows.
        for (wiring in pushWirings) {
            val sourceDevice = devices[wiring.sourceName]
                ?: error("wirePush source device '${wiring.sourceName}' not found in group")
            val targetDevice = devices[wiring.targetName]
                ?: error("wirePush target device '${wiring.targetName}' not found in group")

            scope.launch {
                sourceDevice.propertyChangesFlow(space.kscience.krig.api.context.AnonymousPrincipal, wiring.sourceProperty)
                    .collect { msg ->
                        targetDevice.writeProperty(wiring.targetProperty, msg.value)
                    }
            }
        }

        return composite
    }

    /**
     * Convenience method: materializes the group using the context's own coroutine scope.
     * Returns the composite [Device] directly.
     *
     * @param name The name of the composite device.
     * @param context DataForge context.
     */
    public suspend fun buildAndStart(name: String, context: Context): CompositeDevice =
        start(name, context, CoroutineScope(context.coroutineContext))
}

/**
 * Declaration of a cross-device property wiring (read from one → write to another).
 */
internal data class WiringDeclaration(
    val sourceName: Name,
    val sourceProperty: Name,
    val targetName: Name,
    val targetProperty: Name,
    val tick: Duration,
)

/**
 * Declaration of a push-based cross-device property wiring.
 * Subscribes to property change flow instead of polling.
 */
internal data class PushWiringDeclaration(
    val sourceName: Name,
    val sourceProperty: Name,
    val targetName: Name,
    val targetProperty: Name,
)

/**
 * Build a device group declaration using the DSL.
 * Returns a [DeviceGroupBuilder] — call [DeviceGroupBuilder.start] to materialize.
 */
public fun deviceGroup(builder: DeviceGroupBuilder.() -> Unit): DeviceGroupBuilder =
    DeviceGroupBuilder().apply(builder)
