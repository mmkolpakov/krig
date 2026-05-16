package space.kscience.krig.api.descriptors.attributes

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import space.kscience.krig.api.identifiers.Permission
import space.kscience.krig.api.descriptors.MemberAttribute
import space.kscience.krig.api.descriptors.MemberDescriptor
import space.kscience.krig.api.descriptors.attr
import space.kscience.krig.api.descriptors.attribute
import space.kscience.krig.api.meta.AdapterBinding
import space.kscience.krig.api.meta.MemberTag
import space.kscience.krig.api.spec.ResourceLockSpec
import space.kscience.krig.api.spec.RetryPolicy
import space.kscience.krig.api.meta.serializableToMeta
import space.kscience.dataforge.meta.Meta
import kotlin.time.Duration

/**
 * Attributes related to human-readable metadata, UI generation, and categorization.
 *
 * Engineering units intentionally not declared here. Integrations that need typed units
 * (KotUniL, Measured, …) contribute their own `MemberAttribute` subtype carrying
 * library-specific quantities — the SDK does not hardcode a unit ontology.
 */
@Serializable
@SerialName("attr.metadata")
public data class MetadataAttribute(
    val description: String? = null,
    val help: String? = null,
    val group: String? = null,
    val icon: String? = null,
    val widgetHint: String? = null,
    val tags: Set<MemberTag> = emptySet()
) : MemberAttribute {
    override fun toMeta(): Meta = serializableToMeta(serializer(), this)
}

/**
 * Quick access to the [MetadataAttribute] of a descriptor.
 */
public val MemberDescriptor.metadata: MetadataAttribute?
    get() = attribute()
public val MemberDescriptor.description: String? by attr(MetadataAttribute::description)
public val MemberDescriptor.tags: Set<MemberTag>
    get() = metadata?.tags ?: emptySet()

/**
 * Cross-cutting runtime hints honoured by core [PipelineExecutors][space.kscience.krig.core.pipeline.executeRead].
 * Every field has a paired executor: [timeout] → `withTimeout` wrapper, [latencyBudget] →
 * `LatencyBudgetObserver`, [retryPolicy] → `withResilience`, [requiredLocks] →
 * [acquireAllLocks][space.kscience.krig.core.pipeline.acquireAllLocks].
 *
 * Specialised behavior (caching, persistence, telemetry, streaming) lives in dedicated
 * `MemberAttribute` subtypes contributed by their DeviceFeatureSpec integration — keeping this
 * attribute focused on what the core pipeline actually enforces.
 */
@Serializable
@SerialName("attr.behavior")
public data class BehaviorAttribute(
    val timeout: Duration? = null,
    val latencyBudget: Duration? = null,
    val requiredLocks: List<ResourceLockSpec> = emptyList(),
    val retryPolicy: RetryPolicy? = null,
    val tolerance: Double? = null,
) : MemberAttribute {
    override fun toMeta(): Meta = serializableToMeta(serializer(), this)
}

public val MemberDescriptor.behavior: BehaviorAttribute?
    get() = attribute()
public val MemberDescriptor.timeout: Duration? by attr(BehaviorAttribute::timeout)
public val MemberDescriptor.latencyBudget: Duration? by attr(BehaviorAttribute::latencyBudget)
public val MemberDescriptor.requiredLocks: List<ResourceLockSpec>
    get() = behavior?.requiredLocks ?: emptyList()
public val MemberDescriptor.retryPolicy: RetryPolicy?
    get() = behavior?.retryPolicy
public val MemberDescriptor.tolerance: Double? by attr(BehaviorAttribute::tolerance)

/**
 * Attributes defining security and access control.
 *
 * [readPermissions] and [writePermissions] are metadata for introspection.
 * Current RBAC gates use per-device defaults via
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
) : MemberAttribute {
    override fun toMeta(): Meta = serializableToMeta(serializer(), this)
}

public val MemberDescriptor.access: AccessAttribute?
    get() = attribute()

public val MemberDescriptor.readable: Boolean by attr(true, AccessAttribute::readable)
public val MemberDescriptor.mutable: Boolean by attr(false, AccessAttribute::mutable)
public val MemberDescriptor.readPermissions: Set<Permission>
    get() = access?.readPermissions ?: emptySet()
public val MemberDescriptor.writePermissions: Set<Permission>
    get() = access?.writePermissions ?: emptySet()

/**
 * Attributes for protocol-specific bindings. [AdapterBinding] is a polymorphic extension
 * point: protocol modules contribute their own subtypes (Modbus address, OPC UA NodeId, …).
 */
@Serializable
@SerialName("attr.bindings")
public data class BindingsAttribute(
    val bindings: Map<String, AdapterBinding> = emptyMap()
) : MemberAttribute {
    override fun toMeta(): Meta = serializableToMeta(serializer(), this)
}

public val MemberDescriptor.bindings: Map<String, AdapterBinding>
    get() = attribute<BindingsAttribute>()?.bindings ?: emptyMap()
