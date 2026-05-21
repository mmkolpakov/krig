package space.kscience.krig.assembly

import space.kscience.krig.api.factory.DeviceFactory
import space.kscience.krig.core.contracts.Device
import space.kscience.krig.core.contracts.DeviceBlueprint
import space.kscience.krig.core.features.FeatureCatalog
import space.kscience.krig.core.features.UnknownFeaturePolicy
import space.kscience.krig.core.operations.BlueprintValidationFailedException
import space.kscience.krig.core.operations.BlueprintValidationMessage
import space.kscience.krig.core.operations.validateBlueprint
import space.kscience.krig.core.pipeline.PipelineBuilder
import space.kscience.krig.core.pipeline.materializePipeline
import space.kscience.krig.core.pipeline.wrapWithPipeline
import space.kscience.dataforge.context.Context

/**
 * Strategy for handling [BlueprintValidationMessage]s produced before materialization.
 */
public enum class BlueprintValidationPolicy {
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
 * End-to-end materialization: [DeviceFactory] → validated blueprint → running [Device].
 * Features from the blueprint are matched by id against [features]. Unknown ids fail by
 * default. Validation runs via [BlueprintValidationHook][space.kscience.krig.core.operations.BlueprintValidationHook]s
 * registered in [context] per [validationPolicy]; absence of a validation FeatureSpec module
 * on the classpath is equivalent to [BlueprintValidationPolicy.Skip].
 */
public suspend fun <D : Device, C> DeviceFactory<D, C>.assembleDeviceFromBlueprint(
    context: Context,
    config: C,
    features: FeatureCatalog = FeatureCatalog.Empty,
    unknownFeaturePolicy: UnknownFeaturePolicy = UnknownFeaturePolicy.Fail,
    validationPolicy: BlueprintValidationPolicy = BlueprintValidationPolicy.FailOnError,
    configure: PipelineBuilder.() -> Unit = {},
): Device {
    this.validateOrThrow(context, validationPolicy)
    val builder = materializePipeline(this, features, unknownFeaturePolicy).apply(configure)
    val device = create(context, config)
    return wrapWithPipeline(device, builder, id.toString())
}

/** Validates [this] and wraps an externally-constructed [device] with its operation pipeline. */
public suspend fun DeviceBlueprint<*>.assemblePipeline(
    device: Device,
    context: Context,
    features: FeatureCatalog = FeatureCatalog.Empty,
    unknownFeaturePolicy: UnknownFeaturePolicy = UnknownFeaturePolicy.Fail,
    validationPolicy: BlueprintValidationPolicy = BlueprintValidationPolicy.FailOnError,
    configure: PipelineBuilder.() -> Unit = {},
): Device {
    validateOrThrow(context, validationPolicy)
    val builder = materializePipeline(this, features, unknownFeaturePolicy).apply(configure)
    return wrapWithPipeline(device, builder, id.toString())
}

private fun DeviceBlueprint<*>.validateOrThrow(
    context: Context,
    policy: BlueprintValidationPolicy,
) {
    if (policy == BlueprintValidationPolicy.Skip) return
    val messages = context.validateBlueprint(this)
    val hasFailure = when (policy) {
        BlueprintValidationPolicy.FailOnError -> messages.any {
            it.severity == BlueprintValidationMessage.Severity.ERROR
        }
        BlueprintValidationPolicy.Strict -> messages.isNotEmpty()
        BlueprintValidationPolicy.Skip -> false
    }
    if (hasFailure) throw BlueprintValidationFailedException(this, messages)
}
