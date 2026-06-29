package space.kscience.krig.core.pipeline

import space.kscience.dataforge.context.Context
import space.kscience.dataforge.context.logger
import space.kscience.dataforge.context.warn
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.Name
import space.kscience.krig.api.faults.OperationFaultDetails
import space.kscience.krig.api.faults.ValidationFault
import space.kscience.krig.api.features.PipelineFeatureSpec
import space.kscience.krig.api.lifecycle.LifecycleState
import space.kscience.krig.api.result.OperationOutcome
import space.kscience.krig.api.result.getOrThrow
import space.kscience.krig.api.services.auditService
import space.kscience.krig.api.services.authorizationService
import space.kscience.krig.api.services.identityProvider
import space.kscience.krig.core.ExperimentalKrigApi
import space.kscience.krig.core.InternalKrigApi
import space.kscience.krig.core.capabilities.LifecycleManagingCapability
import space.kscience.krig.core.contracts.Device
import space.kscience.krig.core.contracts.DeviceManifest
import space.kscience.krig.core.features.PipelineFeatureCatalog
import space.kscience.krig.core.features.PipelineFeatureSpecMismatchException
import space.kscience.krig.core.contracts.LifecycleStateHolder
import space.kscience.krig.core.contracts.CapabilityHost
import space.kscience.krig.core.features.UnknownPipelineFeaturePolicy
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
    context: Context,
    autoInstallDefaults: Boolean = true,
): Device {
    if (device is PipelineDevice && builder.isEmpty()) return device

    if (autoInstallDefaults && device !is PipelineDevice) {
        installDefaults(builder, device, deviceName, context)
    }

    val installedCapabilities = builder.capabilities.toList()
    val registry = ResourceLockRegistry()

    val pipelined = PipelineDevice(
        delegate = device,
        operationSpecs = mapOf(
            OperationKinds.Read to builder.operationSpec(OperationKinds.Read),
            OperationKinds.Write to builder.operationSpec(OperationKinds.Write),
            OperationKinds.Action to builder.operationSpec(OperationKinds.Action),
        ),
        readDecorators = builder.readDecorators,
        batchReadDecorators = builder.batchReadDecorators,
        registry = registry,
        capabilities = installedCapabilities,
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
    context: Context,
    deviceName: String = name.toString(),
    autoInstallDefaults: Boolean = true,
    configure: PipelineBuilder.() -> Unit,
): Device = wrapWithPipeline(
    device = this,
    builder = PipelineBuilder().apply(configure),
    deviceName = deviceName,
    context = context,
    autoInstallDefaults = autoInstallDefaults,
)

@OptIn(InternalKrigApi::class)
private fun installDefaults(
    builder: PipelineBuilder,
    device: Device,
    deviceName: String,
    context: Context,
) {
    val authService = context.authorizationService
    val auditService = context.auditService
    val identityProvider = context.identityProvider
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
        add(DeviceAuthorizationGate(deviceName, authService, identityProvider))
    }

    val actionGates = buildList {
        add(LifecycleGate(deviceName, lifecycle))
        if (connection != null) add(ConnectionStateGate(deviceName, connection))
        if (capabilityToggles != null) add(CapabilityGate(deviceName, capabilityToggles))
        add(DeviceAuthorizationGate(deviceName, authService, identityProvider))
    }

    builder.prependGates(OperationKinds.Read, readGates)
    builder.prependGates(OperationKinds.Write, writeGates)
    builder.prependGates(OperationKinds.Action, actionGates)

    // Violations must reach an operator: by default they go to the device context logger.
    val onLatencyViolation: (String) -> Unit = { message -> context.logger.warn { message } }
    val readObservers = mutableListOf<OperationObserver>(
        LatencyBudgetObserver(builder.operationSpec(OperationKinds.Read).defaultLatencyBudget, onLatencyViolation),
    )
    val writeObservers = mutableListOf<OperationObserver>(
        LatencyBudgetObserver(builder.operationSpec(OperationKinds.Write).defaultLatencyBudget, onLatencyViolation),
    )
    val actionObservers = mutableListOf<OperationObserver>(
        LatencyBudgetObserver(builder.operationSpec(OperationKinds.Action).defaultLatencyBudget, onLatencyViolation),
    )

    if (auditService.isActive) {
        val auditSink = BufferedAuditSink(device.deviceScope, auditService)
        readObservers += BufferedAuditObserver(deviceName, auditSink, identityProvider)
        writeObservers += BufferedAuditObserver(deviceName, auditSink, identityProvider)
        actionObservers += BufferedAuditObserver(deviceName, auditSink, identityProvider)
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
    profile: PipelineProfile = PipelineProfile.Production,
): PipelineBuilder = materializePipelineOutcome(
    features = features,
    catalog = catalog,
    unknownPipelineFeaturePolicy = unknownPipelineFeaturePolicy,
    profile = profile,
).getOrThrow()

public fun materializePipelineOutcome(
    features: Map<Name, PipelineFeatureSpec>,
    catalog: PipelineFeatureCatalog = PipelineFeatureCatalog.Empty,
    unknownPipelineFeaturePolicy: UnknownPipelineFeaturePolicy = UnknownPipelineFeaturePolicy.Fail,
    profile: PipelineProfile = PipelineProfile.Production,
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
    // Manifest/feature QoS is the default; the runtime profile overrides it (laminate).
    profile.applyTo(builder)
    return OperationOutcome.Ok(builder)
}

public fun materializePipeline(
    manifest: DeviceManifest,
    catalog: PipelineFeatureCatalog = PipelineFeatureCatalog.Empty,
    unknownPipelineFeaturePolicy: UnknownPipelineFeaturePolicy = UnknownPipelineFeaturePolicy.Fail,
    profile: PipelineProfile = PipelineProfile.Production,
): PipelineBuilder = materializePipeline(
    features = manifest.features,
    catalog = catalog,
    unknownPipelineFeaturePolicy = unknownPipelineFeaturePolicy,
    profile = profile,
)

public fun materializePipelineOutcome(
    manifest: DeviceManifest,
    catalog: PipelineFeatureCatalog = PipelineFeatureCatalog.Empty,
    unknownPipelineFeaturePolicy: UnknownPipelineFeaturePolicy = UnknownPipelineFeaturePolicy.Fail,
    profile: PipelineProfile = PipelineProfile.Production,
): OperationOutcome<PipelineBuilder> = materializePipelineOutcome(
    features = manifest.features,
    catalog = catalog,
    unknownPipelineFeaturePolicy = unknownPipelineFeaturePolicy,
    profile = profile,
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
