package space.kscience.krig.assembly

import space.kscience.krig.api.factory.DeviceFactory
import space.kscience.krig.core.contracts.Device
import space.kscience.krig.core.contracts.DeviceManifest
import space.kscience.krig.core.features.PipelineFeatureCatalog
import space.kscience.krig.core.features.UnknownPipelineFeaturePolicy
import space.kscience.krig.core.operations.ManifestValidationFailedException
import space.kscience.krig.core.operations.ManifestValidationMessage
import space.kscience.krig.core.operations.validateManifest
import space.kscience.krig.core.pipeline.PipelineBuilder
import space.kscience.krig.core.pipeline.materializePipeline
import space.kscience.krig.core.pipeline.wrapWithPipeline
import space.kscience.dataforge.context.Context

/**
 * Strategy for handling [ManifestValidationMessage]s produced before materialization.
 */
public enum class ManifestValidationPolicy {
    /** Ignore validation entirely. Useful for tests and raw spikes. */
    Skip,

    /** Run validation but only fail on ERROR-severity messages. Warnings are silently dropped. Default. */
    FailOnError,

    /** Run validation and fail if any ERROR or WARNING messages are present. */
    Strict,
}

/**
 * Creates a [Device] from [DeviceFactory] and wraps it in an operation pipeline configured by
 * [configure]. Uses the runtime [context] as DataForge context root.
 *
 * ```kotlin
 * val device = ThermoFactory.assembleDevice(context, thermoConfig) {
 *     observeRead(CachedObserver(cache, ttl))
 * }
 * ```
 */
public suspend fun <D : Device, C> DeviceFactory<D, C>.assembleDevice(
    context: Context,
    config: C,
    configure: PipelineBuilder.() -> Unit = {},
): Device {
    val device = create(context, config)
    val builder = PipelineBuilder().apply(configure)
    return wrapWithPipeline(device, builder, id.toString())
}

/**
 * End-to-end materialization: [DeviceFactory] → validated Manifest → running [Device].
 * Features from the Manifest are matched by id against [features]. Unknown ids fail by
 * default. Validation runs via [ManifestValidationHook][space.kscience.krig.core.operations.ManifestValidationHook]s
 * registered in [context] per [validationPolicy]; absence of a validation PipelineFeatureSpec module
 * on the classpath is equivalent to [ManifestValidationPolicy.Skip].
 */
public suspend fun <D : Device, C> DeviceFactory<D, C>.assembleDeviceFromManifest(
    context: Context,
    config: C,
    features: PipelineFeatureCatalog = PipelineFeatureCatalog.Empty,
    unknownPipelineFeaturePolicy: UnknownPipelineFeaturePolicy = UnknownPipelineFeaturePolicy.Fail,
    validationPolicy: ManifestValidationPolicy = ManifestValidationPolicy.FailOnError,
    configure: PipelineBuilder.() -> Unit = {},
): Device {
    this.validateOrThrow(context, validationPolicy)
    val builder = materializePipeline(this, features, unknownPipelineFeaturePolicy).apply(configure)
    val device = create(context, config)
    return wrapWithPipeline(device, builder, id.toString())
}

/** Validates [this] and wraps an externally-constructed [device] with its operation pipeline. */
public suspend fun DeviceManifest.assemblePipeline(
    device: Device,
    context: Context,
    features: PipelineFeatureCatalog = PipelineFeatureCatalog.Empty,
    unknownPipelineFeaturePolicy: UnknownPipelineFeaturePolicy = UnknownPipelineFeaturePolicy.Fail,
    validationPolicy: ManifestValidationPolicy = ManifestValidationPolicy.FailOnError,
    configure: PipelineBuilder.() -> Unit = {},
): Device {
    validateOrThrow(context, validationPolicy)
    val builder = materializePipeline(this, features, unknownPipelineFeaturePolicy).apply(configure)
    return wrapWithPipeline(device, builder, id.toString())
}

private fun DeviceManifest.validateOrThrow(
    context: Context,
    policy: ManifestValidationPolicy,
) {
    if (policy == ManifestValidationPolicy.Skip) return
    val messages = context.validateManifest(this)
    val hasFailure = when (policy) {
        ManifestValidationPolicy.FailOnError -> messages.any {
            it.severity == ManifestValidationMessage.Severity.ERROR
        }
        ManifestValidationPolicy.Strict -> messages.isNotEmpty()
        ManifestValidationPolicy.Skip -> false
    }
    if (hasFailure) throw ManifestValidationFailedException(this, messages)
}
