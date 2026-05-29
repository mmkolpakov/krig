package space.kscience.krig.core.pipeline

import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.Name
import space.kscience.krig.api.faults.OperationFaultDetails
import space.kscience.krig.api.faults.ValidationFault
import space.kscience.krig.api.features.PipelineFeatureSpec
import space.kscience.krig.api.lifecycle.LifecycleState
import space.kscience.krig.api.result.OperationOutcome
import space.kscience.krig.api.result.getOrThrow
import space.kscience.krig.api.result.okUnit
import space.kscience.krig.api.services.auditService
import space.kscience.krig.api.services.authorizationService
import space.kscience.krig.core.ExperimentalKrigApi
import space.kscience.krig.core.InternalKrigApi
import space.kscience.krig.core.capabilities.LifecycleManagingCapability
import space.kscience.krig.core.capabilities.capabilityValues
import space.kscience.krig.core.contracts.Device
import space.kscience.krig.core.contracts.DeviceManifest
import space.kscience.krig.core.features.PipelineFeatureCatalog
import space.kscience.krig.core.features.PipelineFeatureSpecMismatchException
import space.kscience.krig.core.contracts.LifecycleStateHolder
import space.kscience.krig.core.contracts.CapabilityHost
import space.kscience.krig.core.features.UnknownPipelineFeaturePolicy
import space.kscience.krig.core.hook.PropertyReadRequested
import space.kscience.krig.core.operations.ResourceLockRegistry

/**
 * Assembles a [PipelineDevice] from [PipelineBuilder].
 *
 * Installs the default gate / observer set before gates and observers contributed by
 * [PipelineFeatureSpec]s.
 *
 * Without a [LifecycleManagingCapability] the device is promoted to [LifecycleState.Running].
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

/**
 * Opt-in decorator for cross-cutting operation policy around an already materialized device.
 *
 * Use this for proxy/RPC/security wrappers and tests that intentionally need a policy layer.
 * Ordinary local devices should prefer a plain backend plus typed contracts.
 */
@ExperimentalKrigApi
public suspend fun Device.withOperationPipeline(
    deviceName: String = name.toString(),
    autoInstallDefaults: Boolean = true,
    configure: PipelineBuilder.() -> Unit,
): Device = wrapWithPipeline(
    device = this,
    builder = PipelineBuilder().apply(configure),
    deviceName = deviceName,
    autoInstallDefaults = autoInstallDefaults,
)

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
 * [catalog]. Unknown ids are handled by [UnknownPipelineFeaturePolicy].
 */
public fun materializePipeline(
    features: Map<Name, PipelineFeatureSpec>,
    catalog: PipelineFeatureCatalog = PipelineFeatureCatalog.Empty,
    unknownPipelineFeaturePolicy: UnknownPipelineFeaturePolicy = UnknownPipelineFeaturePolicy.Fail,
): PipelineBuilder = materializePipelineOutcome(
    features = features,
    catalog = catalog,
    unknownPipelineFeaturePolicy = unknownPipelineFeaturePolicy,
).getOrThrow()

public fun materializePipelineOutcome(
    features: Map<Name, PipelineFeatureSpec>,
    catalog: PipelineFeatureCatalog = PipelineFeatureCatalog.Empty,
    unknownPipelineFeaturePolicy: UnknownPipelineFeaturePolicy = UnknownPipelineFeaturePolicy.Fail,
): OperationOutcome<PipelineBuilder> {
    val builder = PipelineBuilder()
    for ((id, pipelineFeatureSpec) in features) {
        val pipelineFeature = catalog[id]
        if (pipelineFeature != null) {
            try {
                pipelineFeature.installFromSpec(pipelineFeatureSpec, builder)
            } catch (e: PipelineFeatureSpecMismatchException) {
                return invalidPipelineFeatureSpec(id, pipelineFeatureSpec, e)
            }
        } else {
            val outcome = unknownPipelineFeaturePolicy.handle(id, pipelineFeatureSpec)
            if (outcome is OperationOutcome.Fail) return outcome
        }
    }
    return OperationOutcome.Ok(builder)
}

public fun materializePipeline(
    manifest: DeviceManifest,
    catalog: PipelineFeatureCatalog = PipelineFeatureCatalog.Empty,
    unknownPipelineFeaturePolicy: UnknownPipelineFeaturePolicy = UnknownPipelineFeaturePolicy.Fail,
): PipelineBuilder = materializePipeline(
    features = manifest.features,
    catalog = catalog,
    unknownPipelineFeaturePolicy = unknownPipelineFeaturePolicy,
)

public fun materializePipelineOutcome(
    manifest: DeviceManifest,
    catalog: PipelineFeatureCatalog = PipelineFeatureCatalog.Empty,
    unknownPipelineFeaturePolicy: UnknownPipelineFeaturePolicy = UnknownPipelineFeaturePolicy.Fail,
): OperationOutcome<PipelineBuilder> = materializePipelineOutcome(
    features = manifest.features,
    catalog = catalog,
    unknownPipelineFeaturePolicy = unknownPipelineFeaturePolicy,
)

private fun invalidPipelineFeatureSpec(
    id: Name,
    spec: PipelineFeatureSpec,
    cause: IllegalArgumentException,
): OperationOutcome.Fail =
    OperationOutcome.Fail(
        ValidationFault(
            message = cause.message ?: "PipelineFeatureSpec does not match the registered PipelineFeature.",
            details = Meta {
                "featureId" put id.toString()
                "specType" put (spec::class.simpleName ?: spec::class.toString())
                OperationFaultDetails.MESSAGE put (cause.message ?: "PipelineFeatureSpec does not match the registered PipelineFeature.")
            },
        ),
    )
