package space.kscience.krig.api.descriptors.attributes

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import space.kscience.krig.api.identifiers.Permission
import space.kscience.krig.api.descriptors.OperationAttribute
import space.kscience.krig.api.descriptors.OperationDescriptor
import space.kscience.krig.api.descriptors.attr
import space.kscience.krig.api.descriptors.attribute
import space.kscience.krig.api.meta.AdapterBinding
import space.kscience.krig.api.meta.serializableToMeta
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.Name
import kotlin.time.Duration

/**
 * Human-readable operation metadata.
 *
 * Engineering units intentionally not declared here. Integrations that need typed units
 * (KotUniL, Measured, …) contribute their own `OperationAttribute` subtype carrying
 * library-specific quantities — the SDK does not hardcode a unit ontology.
 */
@Serializable
@SerialName("attr.metadata")
public data class MetadataAttribute(
    val description: String? = null,
    val help: String? = null,
) : OperationAttribute {
    override fun toMeta(): Meta = serializableToMeta(serializer(), this)
}

/**
 * Quick access to the [MetadataAttribute] of a descriptor.
 */
public val OperationDescriptor.metadata: MetadataAttribute?
    get() = attribute()
public val OperationDescriptor.description: String? by attr(MetadataAttribute::description)

/**
 * Cross-cutting runtime hints honoured by the operation pipeline.
 * Every field has a paired runtime hook: [timeout] → timeout wrapper, [latencyBudget] →
 * `LatencyBudgetObserver`, [retryPolicy] → retry wrapper, [requiredLocks] →
 * [acquireAllLocks][space.kscience.krig.core.pipeline.acquireAllLocks],
 * [requiredCapabilities] → capability toggle gates.
 *
 * Specialised behavior (caching, persistence, telemetry, streaming) lives in dedicated
 * `OperationAttribute` subtypes contributed by their FeatureSpec integration — keeping this
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
) : OperationAttribute {
    override fun toMeta(): Meta = serializableToMeta(serializer(), this)
}

public val OperationDescriptor.behavior: BehaviorAttribute?
    get() = attribute()
public val OperationDescriptor.timeout: Duration? by attr(BehaviorAttribute::timeout)
public val OperationDescriptor.latencyBudget: Duration? by attr(BehaviorAttribute::latencyBudget)
public val OperationDescriptor.requiredLocks: List<ResourceLock>
    get() = behavior?.requiredLocks ?: emptyList()
public val OperationDescriptor.requiredCapabilities: Set<Name>
    get() = behavior?.requiredCapabilities ?: emptySet()
public val OperationDescriptor.retryPolicy: RetryPolicy?
    get() = behavior?.retryPolicy
public val OperationDescriptor.tolerance: Double? by attr(BehaviorAttribute::tolerance)

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
) : OperationAttribute {
    override fun toMeta(): Meta = serializableToMeta(serializer(), this)
}

public val OperationDescriptor.access: AccessAttribute?
    get() = attribute()

public val OperationDescriptor.readable: Boolean by attr(true, AccessAttribute::readable)
public val OperationDescriptor.mutable: Boolean by attr(false, AccessAttribute::mutable)
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
) : OperationAttribute {
    override fun toMeta(): Meta = serializableToMeta(serializer(), this)
}

public val OperationDescriptor.bindings: Map<String, AdapterBinding>
    get() = attribute<BindingsAttribute>()?.bindings ?: emptyMap()
