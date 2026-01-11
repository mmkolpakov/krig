package space.kscience.controls.core.contracts

import space.kscience.controls.api.features.Feature
import space.kscience.controls.api.identifiers.BlueprintId
import space.kscience.controls.core.InternalControlsApi
import space.kscience.controls.core.features.FeatureSpec
import space.kscience.controls.core.meta.DeviceActionSpec
import space.kscience.controls.core.meta.DevicePropertySpec
import space.kscience.controls.core.meta.DeviceStreamSpec
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.MetaRepr
import space.kscience.dataforge.misc.DfType
import space.kscience.dataforge.names.Name

/**
 * A complete, self-contained blueprint for a device.
 *
 * Blueprints are designed to be discoverable via context plugins and are serializable to [Meta] (excluding behavior logic).
 *
 * @param D The type of the device this blueprint creates.
 */
@DfType(DeviceBlueprint.TYPE)
public interface DeviceBlueprint<D : Device> : MetaRepr {
    /**
     * A unique identifier for this blueprint, typically in reverse-DNS format (e.g., "com.example.myDevice").
     * This ID is used by a blueprint registry to discover and resolve blueprints at runtime. It should remain
     * constant across different versions of the same logical blueprint.
     */
    public val id: BlueprintId

    /**
     * A version string for this blueprint, preferably using semantic versioning (e.g., "1.0.2").
     * This allows runtimes to handle different versions of a blueprint, enabling features like state migration
     * and compatibility checks.
     */
    public val version: String get() = "0.1.0"

    /**
     * A map of features supported by this device. The key is the unique ID defined by the feature specification.
     */
    public val features: Map<String, Feature>

    /**
     * Retrieves a feature configuration by its type-safe specification.
     * This is the preferred way to access features.
     *
     * @param spec The [FeatureSpec] of the desired feature.
     * @return The feature instance if present, or `null`.
     */
    public operator fun <F : Feature> get(spec: FeatureSpec<F, *>): F? {
        @Suppress("UNCHECKED_CAST")
        return features[spec.id] as? F
    }

    /**
     * A map of all **public** property specifications defined for this device.
     * The key is the property name.
     */
    public val properties: Map<Name, DevicePropertySpec<D, *>>

    /**
     * A map of all **public** action specifications defined for this device.
     */
    public val actions: Map<Name, DeviceActionSpec<D, *, *>>

    /**
     * A map of all **public** data stream specifications defined for this device.
     * The key is the stream name.
     */
    public val streams: Map<Name, DeviceStreamSpec<D>>

    /**
     * Additional metadata for the blueprint itself.
     */
    public val meta: Meta

    /**
     * The fully qualified name of the device contract interface 'D'.
     */
    public val deviceContractFqName: String

    public companion object {
        public const val TYPE: String = "device.blueprint"
    }
}