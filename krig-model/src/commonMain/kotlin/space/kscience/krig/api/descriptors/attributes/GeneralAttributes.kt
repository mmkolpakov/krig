package space.kscience.krig.api.descriptors.attributes

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import space.kscience.attributes.serialization.SerializableAttribute
import space.kscience.krig.api.descriptors.OperationAttributeKey
import space.kscience.krig.api.descriptors.OperationDescriptor
import space.kscience.krig.api.descriptors.attr
import space.kscience.krig.api.descriptors.attribute
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.Name
import kotlin.time.Duration

/**
 * Human-readable operation metadata.
 *
 * [unit] is an optional engineering-unit *label* — a UCUM/UNECE-style code string (e.g. `"Cel"`,
 * `"rpm"`, `"m/s"`), mirroring OPC UA `EUInformation` and AWS SiteWise `unit`. It is a static
 * contract annotation (not a per-sample value) used for display and export metadata (e.g. Arrow
 * column `metadata={"unit": …}`). The SDK deliberately does **not** hardcode a typed unit *ontology*:
 * integrations that need dimensional quantities (KotUniL, Measured, …) layer their own attribute
 * key/value on top; this label is the lightweight interop seam.
 */
@Serializable
@SerialName("attr.metadata")
public data class MetadataAttribute(
    val description: String? = null,
    val unit: String? = null,
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

    public object Task : OperationAttributeKey<TaskAttribute>(
        "attr.task",
        TaskAttribute.serializer(),
    )

    public val standard: Set<SerializableAttribute<*>> = setOf(Metadata, Behavior, Access, Bindings, Task)
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

/** Engineering-unit label (UCUM/UNECE code) of a descriptor, or `null` when unitless. */
public val OperationDescriptor.unit: String? by attr(
    OperationAttributeKeys.Metadata,
    MetadataAttribute::unit,
)

/**
 * Cross-cutting runtime hints honoured by the operation pipeline.
 * Every field has a paired runtime hook: [timeout] → timeout wrapper, [latencyBudget] →
 * `LatencyBudgetObserver`, [retryPolicy] → retry wrapper, [requiredLocks] →
 * [acquireAllLocks][space.kscience.krig.core.pipeline.acquireAllLocks],
 * [requiredCapabilityIds] → capability toggle gates.
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
    val requiredCapabilityIds: Set<Name> = emptySet(),
    val retryPolicy: RetryPolicy? = null,
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
public val OperationDescriptor.requiredCapabilityIds: Set<Name>
    get() = behavior?.requiredCapabilityIds ?: emptySet()
public val OperationDescriptor.retryPolicy: RetryPolicy?
    get() = behavior?.retryPolicy

/**
 * Attributes defining read/write access. RBAC is enforced at runtime via
 * [ControlsPermission][space.kscience.krig.api.identifiers.ControlsPermission];
 * descriptor-level permission metadata is intentionally not modelled here.
 */
@Serializable
@SerialName("attr.access")
public data class AccessAttribute(
    val readable: Boolean = true,
    val mutable: Boolean = false,
)

public val OperationDescriptor.access: AccessAttribute?
    get() = attribute(OperationAttributeKeys.Access)

public val OperationDescriptor.readable: Boolean by attr(
    OperationAttributeKeys.Access,
    default = true,
    AccessAttribute::readable,
)
public val OperationDescriptor.mutable: Boolean by attr(
    OperationAttributeKeys.Access,
    default = false,
    AccessAttribute::mutable,
)

/**
 * Protocol-specific binding configuration, keyed by adapter id (`"modbus"`, `"opcua"`, …).
 * Values are schemaless [Meta]: protocol modules project them to/from their own typed binding
 * at the adapter boundary (DataForge `MetaConverter`/`Scheme`). The core carries no protocol
 * type ontology — the seam is a Meta key convention, not a polymorphic class hierarchy.
 */
@Serializable
@SerialName("attr.bindings")
public data class BindingsAttribute(
    val bindings: Map<String, Meta> = emptyMap()
)

public val OperationDescriptor.bindings: Map<String, Meta>
    get() = attribute(OperationAttributeKeys.Bindings)?.bindings ?: emptyMap()

/** Protocol binding [Meta] for [adapterId], or `null` when the adapter declares none. */
public fun OperationDescriptor.binding(adapterId: String): Meta? = bindings[adapterId]

/**
 * Contract hint for an action that starts or controls a long-running task.
 *
 * The action is still the command/trigger. Task progress is exposed through regular device state
 * and optional task messages so clients do not have to keep a blocking action call open.
 */
@Serializable
@SerialName("attr.task")
public data class TaskAttribute(
    val stateProperty: Name? = null,
    val progressProperty: Name? = null,
    val cancelAction: Name? = null,
    val emitsMessages: Boolean = true,
)

public val OperationDescriptor.task: TaskAttribute?
    get() = attribute(OperationAttributeKeys.Task)

public val OperationDescriptor.taskStateProperty: Name?
    get() = task?.stateProperty

public val OperationDescriptor.taskProgressProperty: Name?
    get() = task?.progressProperty

public val OperationDescriptor.cancelTaskAction: Name?
    get() = task?.cancelAction

public val OperationDescriptor.isLongRunningTask: Boolean
    get() = task != null
