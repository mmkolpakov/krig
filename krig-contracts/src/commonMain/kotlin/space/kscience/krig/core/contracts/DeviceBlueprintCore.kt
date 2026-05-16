package space.kscience.krig.core.contracts

import space.kscience.krig.api.descriptors.ActionDescriptor
import space.kscience.krig.api.descriptors.PropertyDescriptor
import space.kscience.krig.api.features.DeviceFeatureSpec
import space.kscience.krig.api.identifiers.BlueprintId
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.MetaRepr
import space.kscience.dataforge.misc.DfType
import space.kscience.dataforge.names.Name

/**
 * Serializable, self-contained descriptor document for a device.
 *
 * Contains names, types, units, constraints, and DeviceFeatureSpec configurations -- no
 * executable code. Behavioral bindings live in `DevicePropertySpec`/`DeviceActionSpec`.
 *
 * @param D Phantom type for type-safe wiring at the factory/builder level.
 */
@DfType(DeviceBlueprint.TYPE)
public interface DeviceBlueprint<D : Device> : MetaRepr {
    /**
     * A unique identifier for this blueprint, typically in reverse-DNS format.
     * Used by a blueprint registry to discover and resolve blueprints at runtime.
     */
    public val id: BlueprintId

    /** Semantic version of this blueprint; used for state migration and compatibility checks. */
    public val version: String get() = "0.1.0"

    /** Map of features supported by this device, keyed by the DeviceFeatureSpec ID string. */
    public val features: Map<String, DeviceFeatureSpec>

    /**
     * Retrieves a DeviceFeatureSpec configuration by its string ID.
     *
     * @param featureId The unique identifier of the desired DeviceFeatureSpec (e.g. `MetadataFeature.ID`).
     * @return The DeviceFeatureSpec instance if present, or `null`.
     */
    public operator fun get(featureId: String): DeviceFeatureSpec? = features[featureId]

    /** All public property descriptors for this device, keyed by property name. Pure data, no executable code. */
    public val properties: Map<Name, PropertyDescriptor>

    /** All public action descriptors for this device, keyed by action name. Pure data, no executable code. */
    public val actions: Map<Name, ActionDescriptor>

    /** Additional metadata for the blueprint itself. */
    public val meta: Meta

    /** The fully qualified name of the device contract interface `D`. */
    public val deviceContractFqName: String

    public companion object {
        public const val TYPE: String = "device.blueprint"
    }
}

/**
 * Type-safe DeviceFeatureSpec lookup by reified type and companion ID constant.
 *
 * Usage:
 * ```kotlin
 * val meta: MetadataFeature? = blueprint.featureSpec<MetadataFeature>(MetadataFeature.ID)
 * ```
 */
public inline fun <reified F : DeviceFeatureSpec> DeviceBlueprint<*>.featureSpec(featureId: String): F? =
    features[featureId] as? F

/**
 * Type-safe DeviceFeatureSpec requirement. Throws if the DeviceFeatureSpec is absent or has a wrong type.
 */
public inline fun <reified F : DeviceFeatureSpec> DeviceBlueprint<*>.requireFeatureSpec(featureId: String): F =
    featureSpec<F>(featureId)
        ?: error("DeviceBlueprint '$id' requires DeviceFeatureSpec '$featureId' of type ${F::class.simpleName}")
