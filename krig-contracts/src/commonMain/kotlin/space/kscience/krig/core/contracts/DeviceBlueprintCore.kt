package space.kscience.krig.core.contracts

import space.kscience.krig.api.descriptors.ActionDescriptor
import space.kscience.krig.api.descriptors.PropertyDescriptor
import space.kscience.krig.api.features.FeatureSpec
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.MetaRepr
import space.kscience.dataforge.misc.DfType
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName

/**
 * Serializable, self-contained descriptor document for a device.
 *
 * Contains names, types, units, constraints, and FeatureSpec configurations -- no
 * executable code. Typed conversion metadata lives in `DevicePropertyContract` /
 * `DeviceActionContract`; executable bindings live in backends.
 *
 * @param D Phantom type for type-safe wiring at the factory/builder level.
 */
@DfType(DeviceBlueprint.TYPE)
public interface DeviceBlueprint<D : Device> : MetaRepr {
    /**
     * A unique identifier for this blueprint, typically in reverse-DNS format.
     * Used by a blueprint registry to discover and resolve blueprints at runtime.
     */
    public val id: Name

    /** Semantic version of this blueprint; used for state migration and compatibility checks. */
    @Suppress("SameReturnValue")
    public val version: String get() = "0.1.0"

    /** Map of features supported by this device, keyed by stable FeatureSpec id. */
    public val features: Map<Name, FeatureSpec>

    /**
     * Retrieves a FeatureSpec configuration by its string ID.
     *
     * @param featureId The unique identifier of the desired FeatureSpec (e.g. `MetadataFeature.ID`).
     * @return The FeatureSpec instance if present, or `null`.
     */
    public operator fun get(featureId: Name): FeatureSpec? = features[featureId]

    /** String-id overload of [get]. */
    public operator fun get(featureId: String): FeatureSpec? = features[featureId.asName()]

    /** All public property descriptors for this device, keyed by property name. Pure data, no executable code. */
    public val properties: Map<Name, PropertyDescriptor>

    /** All public action descriptors for this device, keyed by action name. Pure data, no executable code. */
    public val actions: Map<Name, ActionDescriptor>

    /** Additional metadata for the blueprint itself. */
    public val meta: Meta

    /** The fully qualified name of the device contract interface `D`. */
    @Suppress("SameReturnValue")
    public val deviceContractFqName: String

    public companion object {
        public const val TYPE: String = "device.blueprint"
    }
}

/**
 * Type-safe FeatureSpec lookup by reified type and companion ID constant.
 *
 * Usage:
 * ```kotlin
 * val meta: MetadataFeature? = blueprint.featureSpec<MetadataFeature>(MetadataFeature.ID)
 * ```
 */
public inline fun <reified F : FeatureSpec> DeviceBlueprint<*>.featureSpec(featureId: Name): F? =
    features[featureId] as? F

/** String-id overload of [featureSpec]. */
public inline fun <reified F : FeatureSpec> DeviceBlueprint<*>.featureSpec(featureId: String): F? =
    featureSpec(featureId.asName())

/**
 * Type-safe FeatureSpec requirement. Throws if the FeatureSpec is absent or has a wrong type.
 */
public inline fun <reified F : FeatureSpec> DeviceBlueprint<*>.requireFeatureSpec(featureId: Name): F =
    featureSpec<F>(featureId)
        ?: error("DeviceBlueprint '$id' requires FeatureSpec '$featureId' of type ${F::class.simpleName}")

/** String-id overload of [requireFeatureSpec]. */
public inline fun <reified F : FeatureSpec> DeviceBlueprint<*>.requireFeatureSpec(featureId: String): F =
    requireFeatureSpec(featureId.asName())
