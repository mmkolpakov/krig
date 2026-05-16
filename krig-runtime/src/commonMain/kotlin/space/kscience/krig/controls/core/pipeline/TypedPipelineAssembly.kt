@file:OptIn(space.kscience.krig.core.InternalKrigApi::class)

package space.kscience.krig.core.pipeline

import space.kscience.krig.api.features.DeviceFeatureSpec
import space.kscience.krig.api.lifecycle.LifecycleState
import space.kscience.krig.api.services.auditService
import space.kscience.krig.api.services.authorizationService
import space.kscience.krig.core.InternalKrigApi
import space.kscience.krig.core.capabilities.LifecycleManagingCapability
import space.kscience.krig.core.capabilities.capabilityValues
import space.kscience.krig.core.contracts.Device
import space.kscience.krig.core.contracts.DeviceBlueprint
import space.kscience.krig.core.contracts.DeviceFeatureInstaller
import space.kscience.krig.core.contracts.LifecycleStateHolder
import space.kscience.krig.core.contracts.RuntimeCapabilityHost
import space.kscience.krig.core.hook.PropertyReadRequested
import space.kscience.krig.core.operations.ResourceLockRegistry

/**
 * Assembles a [TypedPipelineDevice] from [TypedPipelineBuilder].
 *
 * Auto-installs the canonical gate / observer set on top of DeviceFeatureSpec-contributed gates and
 * observers (lifecycle gate, RBAC for writes / actions, optional connection-state gate,
 * latency-budget observer, optional audit observer). DeviceFeatureSpec gates and observers
 * run after the defaults.
 *
 * Without a [LifecycleManagingCapability] the device auto-promotes to [LifecycleState.Running]
 * so trivial DSL flows work out of the box.
 */
@OptIn(InternalKrigApi::class)
public suspend fun wrapWithTypedPipeline(
    device: Device,
    builder: TypedPipelineBuilder,
    deviceName: String,
    autoInstallDefaults: Boolean = true,
): Device {
    if (device is TypedPipelineDevice && builder.isEmpty()) return device

    // PropertyReadRequested hook fires BEFORE the default gates so observability counts
    // every requested read — including those rejected by lifecycle / RBAC / connection gates.
    val readRequestedHandlers = builder.handlersOf(PropertyReadRequested)
    if (readRequestedHandlers.isNotEmpty()) {
        builder.addReadGate(ReadGate { spec ->
            for (handler in readRequestedHandlers) handler(spec.name)
        })
    }

    if (autoInstallDefaults && device !is TypedPipelineDevice) installDefaults(builder, device, deviceName)

    val capabilitiesSnapshot = builder.capabilities.attributes()
    val installedCapabilities = capabilitiesSnapshot.capabilityValues
    val registry = ResourceLockRegistry()
    val capabilityHost = device as? RuntimeCapabilityHost
    for (cap in installedCapabilities) {
        capabilityHost?.installCapability(cap)
    }

    val pipelined = TypedPipelineDevice(
        delegate = device,
        readSpec = builder.toReadSpec(),
        writeSpec = builder.toWriteSpec(),
        actionSpec = builder.toActionSpec(),
        registry = registry,
        capabilities = capabilitiesSnapshot,
    )

    val hasLifecycleCapability = installedCapabilities.any { it is LifecycleManagingCapability }

    for (cap in installedCapabilities) {
        with(pipelined as Device) { cap.onAttach() }
    }

    if (!hasLifecycleCapability) {
        (pipelined as? LifecycleStateHolder)?.updateLifecycleState(LifecycleState.Running)
    }
    return pipelined
}

@OptIn(InternalKrigApi::class)
private fun installDefaults(builder: TypedPipelineBuilder, device: Device, deviceName: String) {
    val authService = device.context.authorizationService
    val auditService = device.context.auditService
    val lifecycle: () -> LifecycleState = { device.lifecycleState }
    val connection = builder.connectionStateProvider

    // Lifecycle gates always.
    builder.addReadGate(LifecycleReadGate(deviceName, lifecycle))
    builder.addWriteGate(LifecycleWriteGate(deviceName, lifecycle))
    builder.addActionGate(LifecycleActionGate(deviceName, lifecycle))

    // Connection-state gates only when supplier is configured.
    if (connection != null) {
        builder.addReadGate(ConnectionStateReadGate(deviceName, connection))
        builder.addWriteGate(ConnectionStateWriteGate(deviceName, connection))
        builder.addActionGate(ConnectionStateActionGate(deviceName, connection))
    }

    // RBAC mandatory on writes / actions.
    builder.addWriteGate(RbacWriteGate(deviceName, authService))
    builder.addActionGate(RbacActionGate(deviceName, authService))

    // Latency-budget observers always (no-op when descriptor doesn't declare a budget).
    builder.addReadObserver(LatencyBudgetReadObserver(builder.readDefaultLatencyBudget))
    builder.addWriteObserver(LatencyBudgetWriteObserver(builder.writeDefaultLatencyBudget))
    builder.addActionObserver(LatencyBudgetActionObserver(builder.actionDefaultLatencyBudget))

    // Audit observers when audit service is active.
    if (auditService.isActive) {
        builder.addReadObserver(AuditReadObserver(deviceName, auditService))
        builder.addWriteObserver(AuditWriteObserver(deviceName, auditService))
        builder.addActionObserver(AuditActionObserver(deviceName, auditService))
    }
}

/**
 * Materialises a typed [features] map into a [TypedPipelineBuilder] by matching each DTO
 * against [installers] by [DeviceFeatureInstaller.id]. Unknown ids are silently skipped.
 *
 * Replaces the legacy `materializePipeline`.
 */
public fun materializeTypedPipeline(
    features: Map<String, DeviceFeatureSpec>,
    installers: Iterable<DeviceFeatureInstaller<*, *>>,
): TypedPipelineBuilder {
    val byId = installers.associateBy { it.id }
    val builder = TypedPipelineBuilder()
    for ((id, featureSpec) in features) {
        byId[id]?.installFromFeature(featureSpec, builder)
    }
    return builder
}

public fun materializeTypedPipeline(
    blueprint: DeviceBlueprint<*>,
    installers: Iterable<DeviceFeatureInstaller<*, *>>,
): TypedPipelineBuilder = materializeTypedPipeline(blueprint.features, installers)
