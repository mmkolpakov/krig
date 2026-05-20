package space.kscience.krig.core.pipeline

import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.Name
import space.kscience.krig.api.faults.ValidationFault
import space.kscience.krig.api.features.FeatureSpec
import space.kscience.krig.api.lifecycle.LifecycleState
import space.kscience.krig.api.result.OperationOutcome
import space.kscience.krig.api.result.getOrThrow
import space.kscience.krig.api.result.okUnit
import space.kscience.krig.api.services.auditService
import space.kscience.krig.api.services.authorizationService
import space.kscience.krig.core.InternalKrigApi
import space.kscience.krig.core.capabilities.LifecycleManagingCapability
import space.kscience.krig.core.capabilities.capabilityValues
import space.kscience.krig.core.contracts.Device
import space.kscience.krig.core.contracts.DeviceBlueprint
import space.kscience.krig.core.contracts.FeatureCatalog
import space.kscience.krig.core.contracts.FeatureSpecMismatchException
import space.kscience.krig.core.contracts.LifecycleStateHolder
import space.kscience.krig.core.contracts.CapabilityHost
import space.kscience.krig.core.contracts.UnknownFeaturePolicy
import space.kscience.krig.core.hook.PropertyReadRequested
import space.kscience.krig.core.operations.ResourceLockRegistry

/**
 * Assembles a [PipelineDevice] from [PipelineBuilder].
 *
 * Auto-installs the canonical gate / observer set on top of FeatureSpec-contributed gates and
 * observers (lifecycle gate, RBAC for writes / actions, optional connection-state gate,
 * latency-budget observer, optional audit observer). FeatureSpec gates and observers
 * run after the defaults.
 *
 * Without a [LifecycleManagingCapability] the device auto-promotes to [LifecycleState.Running]
 * so trivial DSL flows work out of the box.
 */
@OptIn(InternalKrigApi::class)
public suspend fun wrapWithPipeline(
    device: Device,
    builder: PipelineBuilder,
    deviceName: String,
    autoInstallDefaults: Boolean = true,
): Device {
    if (device is PipelineDevice && builder.isEmpty()) return device

    val readRequestedHandlers = builder.handlersOf(PropertyReadRequested)
    if (readRequestedHandlers.isNotEmpty()) {
        builder.onRead { operation ->
            for (handler in readRequestedHandlers) handler(operation.name)
            okUnit()
        }
    }

    if (autoInstallDefaults && device !is PipelineDevice) {
        installDefaults(builder, device, deviceName)
    }

    val capabilitiesSnapshot = builder.capabilities.attributes()
    val installedCapabilities = capabilitiesSnapshot.capabilityValues
    val registry = ResourceLockRegistry()

    val pipelined = PipelineDevice(
        delegate = device,
        operationSpecs = mapOf(
            OperationKinds.Read to builder.operationSpec(OperationKinds.Read),
            OperationKinds.Write to builder.operationSpec(OperationKinds.Write),
            OperationKinds.Action to builder.operationSpec(OperationKinds.Action),
        ),
        readDecorators = builder.readDecorators,
        registry = registry,
        capabilities = capabilitiesSnapshot,
    )

    val hasLifecycleCapability = installedCapabilities.any { it is LifecycleManagingCapability }

    for (cap in installedCapabilities) {
        context(pipelined as CapabilityHost) { cap.onAttach() }
    }

    if (!hasLifecycleCapability) {
        (pipelined as? LifecycleStateHolder)?.updateLifecycleState(LifecycleState.Running)
    }
    return pipelined
}

@OptIn(InternalKrigApi::class)
private fun installDefaults(
    builder: PipelineBuilder,
    device: Device,
    deviceName: String,
) {
    val authService = device.context.authorizationService
    val auditService = device.context.auditService
    val lifecycle: () -> LifecycleState = { device.lifecycleState }
    val connection = builder.connectionStateProvider
    val capabilityToggles = (device as? CapabilityHost)?.capabilityToggles

    val readGates = buildList {
        add(LifecycleGate(deviceName, lifecycle))
        if (connection != null) add(ConnectionStateGate(deviceName, connection))
        if (capabilityToggles != null) add(CapabilityGate(deviceName, capabilityToggles))
    }

    val writeGates = buildList {
        add(LifecycleGate(deviceName, lifecycle))
        if (connection != null) add(ConnectionStateGate(deviceName, connection))
        if (capabilityToggles != null) add(CapabilityGate(deviceName, capabilityToggles))
        add(DeviceAuthorizationGate(deviceName, authService))
    }

    val actionGates = buildList {
        add(LifecycleGate(deviceName, lifecycle))
        if (connection != null) add(ConnectionStateGate(deviceName, connection))
        if (capabilityToggles != null) add(CapabilityGate(deviceName, capabilityToggles))
        add(DeviceAuthorizationGate(deviceName, authService))
    }

    builder.prependGates(OperationKinds.Read, readGates)
    builder.prependGates(OperationKinds.Write, writeGates)
    builder.prependGates(OperationKinds.Action, actionGates)

    val readObservers = mutableListOf<OperationObserver>(
        LatencyBudgetObserver(builder.operationSpec(OperationKinds.Read).defaultLatencyBudget),
    )
    val writeObservers = mutableListOf<OperationObserver>(
        LatencyBudgetObserver(builder.operationSpec(OperationKinds.Write).defaultLatencyBudget),
    )
    val actionObservers = mutableListOf<OperationObserver>(
        LatencyBudgetObserver(builder.operationSpec(OperationKinds.Action).defaultLatencyBudget),
    )

    if (auditService.isActive) {
        val auditSink = BufferedAuditSink(device.deviceScope, auditService)
        readObservers += BufferedAuditObserver(deviceName, auditSink)
        writeObservers += BufferedAuditObserver(deviceName, auditSink)
        actionObservers += BufferedAuditObserver(deviceName, auditSink)
    }

    builder.prependObservers(OperationKinds.Read, readObservers)
    builder.prependObservers(OperationKinds.Write, writeObservers)
    builder.prependObservers(OperationKinds.Action, actionObservers)
}

/**
 * Materialises [features] into a [PipelineBuilder] by matching each DTO against
 * [catalog]. Unknown ids are handled by [unknownFeaturePolicy].
 */
public fun materializePipeline(
    features: Map<Name, FeatureSpec>,
    catalog: FeatureCatalog = FeatureCatalog.Empty,
    unknownFeaturePolicy: UnknownFeaturePolicy = UnknownFeaturePolicy.Fail,
): PipelineBuilder = materializePipelineOutcome(
    features = features,
    catalog = catalog,
    unknownFeaturePolicy = unknownFeaturePolicy,
).getOrThrow()

public fun materializePipelineOutcome(
    features: Map<Name, FeatureSpec>,
    catalog: FeatureCatalog = FeatureCatalog.Empty,
    unknownFeaturePolicy: UnknownFeaturePolicy = UnknownFeaturePolicy.Fail,
): OperationOutcome<PipelineBuilder> {
    val builder = PipelineBuilder()
    for ((id, featureSpec) in features) {
        val feature = catalog[id]
        if (feature != null) {
            try {
                feature.installFromSpec(featureSpec, builder)
            } catch (e: FeatureSpecMismatchException) {
                return invalidFeatureSpec(id, featureSpec, e)
            }
        } else {
            val outcome = unknownFeaturePolicy.handle(id, featureSpec)
            if (outcome is OperationOutcome.Fail) return outcome
        }
    }
    return OperationOutcome.Ok(builder)
}

public fun materializePipeline(
    blueprint: DeviceBlueprint<*>,
    catalog: FeatureCatalog = FeatureCatalog.Empty,
    unknownFeaturePolicy: UnknownFeaturePolicy = UnknownFeaturePolicy.Fail,
): PipelineBuilder = materializePipeline(
    features = blueprint.features,
    catalog = catalog,
    unknownFeaturePolicy = unknownFeaturePolicy,
)

public fun materializePipelineOutcome(
    blueprint: DeviceBlueprint<*>,
    catalog: FeatureCatalog = FeatureCatalog.Empty,
    unknownFeaturePolicy: UnknownFeaturePolicy = UnknownFeaturePolicy.Fail,
): OperationOutcome<PipelineBuilder> = materializePipelineOutcome(
    features = blueprint.features,
    catalog = catalog,
    unknownFeaturePolicy = unknownFeaturePolicy,
)

private fun invalidFeatureSpec(
    id: Name,
    spec: FeatureSpec,
    cause: IllegalArgumentException,
): OperationOutcome.Fail =
    OperationOutcome.Fail(
        ValidationFault(
            message = cause.message ?: "FeatureSpec does not match the registered Feature.",
            details = Meta {
                "featureId" put id.toString()
                "specType" put (spec::class.simpleName ?: spec::class.toString())
                "message" put (cause.message ?: "FeatureSpec does not match the registered Feature.")
            },
        ),
    )
