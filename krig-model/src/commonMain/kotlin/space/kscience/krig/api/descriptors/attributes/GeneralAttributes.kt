package space.kscience.krig.api.descriptors.attributes

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import space.kscience.attributes.serialization.SerializableAttribute
import space.kscience.krig.api.identifiers.Permission
import space.kscience.krig.api.descriptors.OperationAttributeKey
import space.kscience.krig.api.descriptors.OperationDescriptor
import space.kscience.krig.api.descriptors.attr
import space.kscience.krig.api.descriptors.attribute
import space.kscience.krig.api.meta.AdapterBinding
import space.kscience.dataforge.names.Name
import kotlin.time.Duration

/**
 * Human-readable operation metadata.
 *
 * Engineering units intentionally not declared here. Integrations that need typed units
 * (KotUniL, Measured, …) contribute their own attribute key/value pair carrying
 * library-specific quantities — the SDK does not hardcode a unit ontology.
 */
@Serializable
@SerialName("attr.metadata")
public data class MetadataAttribute(
    val description: String? = null,
    val help: String? = null,
)

public object OperationAttributeKeys {
    public object Metadata : OperationAttributeKey<MetadataAttribute>(
        "attr.metadata",
        MetadataAttribute.serializer(),
    )

    public object Behavior : OperationAttributeKey<BehaviorAttribute>(
        "attr.behavior",
        BehaviorAttribute.serializer(),
    )

    public object Access : OperationAttributeKey<AccessAttribute>(
        "attr.access",
        AccessAttribute.serializer(),
    )

    public object Bindings : OperationAttributeKey<BindingsAttribute>(
        "attr.bindings",
        BindingsAttribute.serializer(),
    )

    public val standard: Set<SerializableAttribute<*>> = setOf(Metadata, Behavior, Access, Bindings)
}

/**
 * Quick access to the [MetadataAttribute] of a descriptor.
 */
public val OperationDescriptor.metadata: MetadataAttribute?
    get() = attribute(OperationAttributeKeys.Metadata)
public val OperationDescriptor.description: String? by attr(
    OperationAttributeKeys.Metadata,
    MetadataAttribute::description,
)

/**
 * Cross-cutting runtime hints honoured by the operation pipeline.
 * Every field has a paired runtime hook: [timeout] → timeout wrapper, [latencyBudget] →
 * `LatencyBudgetObserver`, [retryPolicy] → retry wrapper, [requiredLocks] →
 * [acquireAllLocks][space.kscience.krig.core.pipeline.acquireAllLocks],
 * [requiredCapabilities] → capability toggle gates.
 *
 * Specialised behavior (caching, persistence, time-series storage, streaming) lives in dedicated
 * attribute key/value pairs contributed by their FeatureSpec integration — keeping this
 * attribute focused on what the core pipeline actually enforces.
 */
@Serializable
@SerialName("attr.behavior")
public data class BehaviorAttribute(
    val timeout: Duration? = null,
    val latencyBudget: Duration? = null,
    val requiredLocks: List<ResourceLock> = emptyList(),
    val requiredCapabilities: Set<Name> = emptySet(),
    val retryPolicy: RetryPolicy? = null,
    val tolerance: Double? = null,
)

public val OperationDescriptor.behavior: BehaviorAttribute?
    get() = attribute(OperationAttributeKeys.Behavior)
public val OperationDescriptor.timeout: Duration? by attr(
    OperationAttributeKeys.Behavior,
    BehaviorAttribute::timeout,
)
public val OperationDescriptor.latencyBudget: Duration? by attr(
    OperationAttributeKeys.Behavior,
    BehaviorAttribute::latencyBudget,
)
public val OperationDescriptor.requiredLocks: List<ResourceLock>
    get() = behavior?.requiredLocks ?: emptyList()
public val OperationDescriptor.requiredCapabilities: Set<Name>
    get() = behavior?.requiredCapabilities ?: emptySet()
public val OperationDescriptor.retryPolicy: RetryPolicy?
    get() = behavior?.retryPolicy
public val OperationDescriptor.tolerance: Double? by attr(
    OperationAttributeKeys.Behavior,
    BehaviorAttribute::tolerance,
)

/**
 * Attributes defining security and access control.
 *
 * [readPermissions] and [writePermissions] are metadata for introspection.
 * Current RBAC gates use device-backed defaults via
 * [ControlsPermissions][space.kscience.krig.api.identifiers.ControlsPermissions].
 * Descriptor-level enforcement is planned for a future release.
 */
@Serializable
@SerialName("attr.access")
public data class AccessAttribute(
    val readable: Boolean = true,
    val mutable: Boolean = false,
    val readPermissions: Set<Permission> = emptySet(),
    val writePermissions: Set<Permission> = emptySet()
)

public val OperationDescriptor.access: AccessAttribute?
    get() = attribute(OperationAttributeKeys.Access)

public val OperationDescriptor.readable: Boolean by attr(
    true,
    OperationAttributeKeys.Access,
    AccessAttribute::readable,
)
public val OperationDescriptor.mutable: Boolean by attr(
    false,
    OperationAttributeKeys.Access,
    AccessAttribute::mutable,
)
public val OperationDescriptor.readPermissions: Set<Permission>
    get() = access?.readPermissions ?: emptySet()
public val OperationDescriptor.writePermissions: Set<Permission>
    get() = access?.writePermissions ?: emptySet()

/**
 * Attributes for protocol-specific bindings. [AdapterBinding] is a polymorphic extension
 * point: protocol modules contribute their own subtypes (Modbus address, OPC UA NodeId, …).
 */
@Serializable
@SerialName("attr.bindings")
public data class BindingsAttribute(
    val bindings: Map<String, AdapterBinding> = emptyMap()
)

public val OperationDescriptor.bindings: Map<String, AdapterBinding>
    get() = attribute(OperationAttributeKeys.Bindings)?.bindings ?: emptyMap()
