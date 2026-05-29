package space.kscience.krig.core.contracts

import space.kscience.krig.api.descriptors.ActionDescriptor
import space.kscience.krig.api.descriptors.PropertyDescriptor
import space.kscience.krig.api.features.PipelineFeatureSpec
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.misc.DfType
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName

/**
 * Serializable descriptor for a device.
 *
 * Contains names, types, units, constraints, and [PipelineFeatureSpec] configurations.
 * Typed converters stay in `DevicePropertyContract` / `DeviceActionContract`;
 * executable bindings stay in backends.
 */
@DfType(DeviceManifest.TYPE)
public interface DeviceManifest {
    /** Unique manifest id, usually in reverse-DNS form. */
    public val id: Name

    /** Manifest version used by catalogs and migration tooling. */
    public val version: String get() = "0.1.0"

    /** Feature specs keyed by their [PipelineFeatureSpec] id. */
    public val features: Map<Name, PipelineFeatureSpec>

    /** Retrieves a [PipelineFeatureSpec] by id. */
    public operator fun get(featureId: Name): PipelineFeatureSpec? = features[featureId]

    /** String-id overload of [get]. */
    public operator fun get(featureId: String): PipelineFeatureSpec? = features[featureId.asName()]

    /** Public property descriptors keyed by property name. */
    public val properties: Map<Name, PropertyDescriptor>

    /** Public action descriptors keyed by action name. */
    public val actions: Map<Name, ActionDescriptor>

    /** Additional manifest metadata. */
    public val meta: Meta

    /** Optional fully qualified name of the device contract interface this manifest exports. */
    public val deviceContractFqName: String

    public companion object {
        public const val TYPE: String = "device.manifest"
    }
}

/**
 * Type-safe [PipelineFeatureSpec] lookup by id.
 *
 * Usage:
 * ```kotlin
 * val meta: MetadataFeature? = manifest.featureSpec<MetadataFeature>(MetadataFeature.ID)
 * ```
 */
public inline fun <reified F : PipelineFeatureSpec> DeviceManifest.featureSpec(featureId: Name): F? =
    features[featureId] as? F

/** String-id overload of [featureSpec]. */
public inline fun <reified F : PipelineFeatureSpec> DeviceManifest.featureSpec(featureId: String): F? =
    featureSpec(featureId.asName())

/**
 * Type-safe [PipelineFeatureSpec] requirement.
 */
public inline fun <reified F : PipelineFeatureSpec> DeviceManifest.requirePipelineFeatureSpec(featureId: Name): F =
    featureSpec<F>(featureId)
        ?: error("DeviceManifest '$id' requires PipelineFeatureSpec '$featureId' of type ${F::class.simpleName}")

/** String-id overload of [requirePipelineFeatureSpec]. */
public inline fun <reified F : PipelineFeatureSpec> DeviceManifest.requirePipelineFeatureSpec(featureId: String): F =
    requirePipelineFeatureSpec(featureId.asName())
