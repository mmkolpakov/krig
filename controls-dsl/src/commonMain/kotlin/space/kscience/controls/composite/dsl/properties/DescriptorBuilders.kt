@file:OptIn(DFExperimental::class)

package space.kscience.controls.composite.dsl.properties

import kotlinx.serialization.serializer
import ru.nsk.kstatemachine.event.Event
import space.kscience.controls.api.descriptors.MemberAttribute
import space.kscience.controls.api.descriptors.attributes.AccessAttribute
import space.kscience.controls.api.descriptors.attributes.BehaviorAttribute
import space.kscience.controls.api.descriptors.attributes.BindingsAttribute
import space.kscience.controls.api.descriptors.attributes.FsmAttribute
import space.kscience.controls.api.descriptors.attributes.ImplementationAttribute
import space.kscience.controls.api.descriptors.attributes.MetadataAttribute
import space.kscience.controls.api.descriptors.attributes.PersistenceAttribute
import space.kscience.controls.api.descriptors.attributes.TelemetryAttribute
import space.kscience.controls.api.descriptors.attributes.ValidationAttribute
import space.kscience.controls.composite.dsl.CompositeSpecDsl
import space.kscience.controls.api.spec.CachePolicy
import space.kscience.controls.api.spec.CacheScope
import space.kscience.controls.api.faults.DeviceFault
import space.kscience.controls.api.spec.ResourceLockSpec
import space.kscience.controls.api.spec.LockType
import space.kscience.controls.api.identifiers.Permission
import space.kscience.controls.api.descriptors.ActionDescriptor
import space.kscience.controls.api.descriptors.PropertyDescriptor
import space.kscience.controls.api.meta.MemberTag
import space.kscience.controls.api.meta.AliasTag
import space.kscience.controls.api.meta.AdapterBinding
import space.kscience.controls.api.descriptors.PropertyKind
import space.kscience.controls.api.meta.ActionOutputSpec
import space.kscience.controls.core.meta.DevicePropertySpec
import space.kscience.controls.api.validation.ValidationRuleDescriptor
import space.kscience.dataforge.meta.*
import space.kscience.dataforge.meta.descriptors.MetaDescriptor
import space.kscience.dataforge.meta.descriptors.MetaDescriptorBuilder
import space.kscience.dataforge.misc.DFExperimental
import space.kscience.dataforge.names.Name
import kotlin.time.Duration

/**
 * A helper DSL class for defining permissions in a structured way.
 */
@CompositeSpecDsl
public class PermissionBuilder {
    internal val permissions = mutableSetOf<Permission>()

    /**
     * A pseudo-property to assign a permission ID for read access.
     */
    public var read: String? = null
        set(value) {
            if (value != null) permissions.add(Permission(value))
            field = value
        }

    /**
     * A pseudo-property to assign a permission ID for write access.
     */
    public var write: String? = null
        set(value) {
            if (value != null) permissions.add(Permission(value))
            field = value
        }

    /**
     * A pseudo-property to assign a permission ID for execute access.
     */
    public var execute: String? = null
        set(value) {
            if (value != null) permissions.add(Permission(value))
            field = value
        }

    /**
     * Adds a custom permission by its ID.
     */
    public fun add(permissionId: String) {
        permissions.add(Permission(permissionId))
    }
}

/**
 * A mutable builder for creating [PropertyDescriptor] instances in a DSL.
 */
@CompositeSpecDsl
public class PropertyDescriptorBuilder(public val name: Name) {
    /**
     * A human-readable description for the property.
     */
    public var description: String? = null

    /**
     * A detailed help string for metrics associated with this property.
     * This will be used as the `HELP` text in Prometheus exports.
     */
    public var help: String? = null

    /**
     * The default timeout for both read and write operations on this property.
     * The runtime is expected to enforce this constraint. If null, a system-wide default may be used.
     */
    public var timeout: Duration? = null

    @PublishedApi
    internal val _requiredLocks: MutableList<ResourceLockSpec> = mutableListOf()

    /**
     * The semantic kind of the property. This field is set automatically by the
     * corresponding property delegate (`property`, `stateProperty`, etc.) and should
     * not be set manually.
     */
    @PublishedApi
    internal var kind: PropertyKind? = null

    /**
     * Storage for serializable validation rules.
     * This list is populated by the `mutableProperty` delegate via the `validation` block.
     */
    public val validationRules: MutableList<ValidationRuleDescriptor> = mutableListOf()

    /**
     * A [MetaDescriptor] defining the structure, type, and constraints of the property's value.
     */
    public var metaDescriptor: MetaDescriptor = MetaDescriptor()

    /**
     * Specifies whether the property is readable. Defaults to `true`.
     */
    public var readable: Boolean = true

    /**
     * A set of permissions required to access this property.
     */
    public val permissions: MutableSet<Permission> = mutableSetOf()

    /**
     * A map of static labels for metrics associated with this property.
     */
    public val labels: MutableMap<String, String> = mutableMapOf()

    /**
     * A list of specific allowed values for this property. If set, the runtime may use this for validation.
     */
    public var allowedValues: List<Value>? = null

    /**
     * A [Meta] block for configuring metrics collection for this property (e.g., enabling counters, gauges).
     */
    public val metrics: MutableMeta = MutableMeta()

    /**
     * If true, this property's state will be included in device snapshots
     * and will be restored when the device starts if persistence is enabled.
     * Defaults to false.
     */
    public var persistent: Boolean = false

    /**
     * If true, this property will be excluded from state snapshots.
     * Defaults to false.
     */
    public var transient: Boolean = false

    /**
     * An optional string representing the physical unit of the property's value (e.g., "volts", "mm", "deg").
     */
    public var unit: String? = null

    /**
     * An optional range constraint for numeric properties, useful for UI widgets like sliders.
     */
    public var range: ClosedFloatingPointRange<Double>? = null

    /**
     * A hint for UI generators suggesting a preferred widget type (e.g., "slider", "checkbox").
     */
    public var widgetHint: String? = null

    /**
     * An optional string for grouping related properties in a user interface.
     */
    public var group: String? = null

    /**
     * An optional string identifier for a visual icon, used by UIs.
     */
    public var icon: String? = null

    private val _tags = mutableSetOf<MemberTag>()
    /**
     * A read-only view of the semantic tags added to this property.
     */
    public val tags: Set<MemberTag> get() = _tags

    private val _bindings = mutableMapOf<String, AdapterBinding>()
    /**
     * A read-only view of the protocol-specific bindings attached to this property.
     */
    public val bindings: Map<String, AdapterBinding> get() = _bindings

    /**
     * Adds a semantic [MemberTag] to this property. Tags are used by external systems
     * like UI generators or documentation tools to classify and handle the property
     * in a context-aware manner.
     *
     * @param tag The [MemberTag] instance to add.
     */
    public fun addTag(tag: MemberTag) {
        _tags.add(tag)
    }

    /**
     * Adds a protocol or system-specific alias for this property.
     * @param namespace A string that uniquely identifies the context for this alias (e.g., "modbus", "json-api").
     * @param alias The alternative name or identifier within that namespace.
     */
    public fun alias(namespace: String, alias: String) {
        addTag(AliasTag(namespace, alias))
    }

    /**
     * Attaches a type-safe, protocol-specific configuration block ([AdapterBinding]) to this property.
     * This is the core mechanism that allows external adapter modules to provide their own
     * configuration DSLs.
     *
     * @param key A unique string identifying the protocol adapter (e.g., "modbus", "yandex").
     * @param binding The serializable [AdapterBinding] configuration object.
     */
    public fun attachBinding(key: String, binding: AdapterBinding) {
        _bindings[key] = binding
    }

    /**
     * Declares that accessing this property requires acquiring a lock on a shared resource.
     * The runtime is responsible for implementing a lock manager to honor this request.
     *
     * @param resourceName The hierarchical [Name] of the resource to lock.
     * @param type The type of lock required (`SHARED_READ` or `EXCLUSIVE_WRITE`).
     */
    public fun requiresLock(resourceName: Name, type: LockType) {
        _requiredLocks.add(ResourceLockSpec(resourceName, type))
    }

    /**
     * A convenience overload for `requiresLock` that accepts a `String` for the resource name.
     *
     * @param resourceName The string representation of the resource name, which will be parsed to a [Name].
     * @param type The type of lock required.
     */
    public fun requiresLock(resourceName: String, type: LockType): Unit =
        requiresLock(Name.parse(resourceName), type)

    /**
     * Configures the metadata descriptor for the property's value using a raw [MutableMeta] block.
     * Also propagates [allowedValues] to the meta descriptor for unified validation.
     */
    public fun meta(block: MetaDescriptorBuilder.() -> Unit) {
        this.metaDescriptor = MetaDescriptor {
            block()
            // Automatically add allowedValues to the meta descriptor if they are set
            this@PropertyDescriptorBuilder.allowedValues?.let {
                this.allowedValues = it
            }
        }
    }

    /**
     * Configures the metadata descriptor for the property's value using a type-safe [SchemeSpec].
     * The descriptor will be derived from the scheme's properties.
     *
     * @param spec The [SchemeSpec] companion object for your configuration scheme.
     * @param block An optional lambda to further customize the generated [MetaDescriptor].
     */
    public fun <S : Scheme> meta(spec: SchemeSpec<S>, block: MetaDescriptorBuilder.() -> Unit = {}) {
        this.metaDescriptor = MetaDescriptor {
            spec.descriptor?.let { from(it) }
            block()
        }
    }


    /**
     * Configures metrics for this property. The runtime will use this meta
     * to set up counters, gauges, etc.
     *
     * Example:
     * ```
     * metrics {
     *     "gauge.enabled" put true
     *     "counter.reads.enabled" put true
     * }
     * ```
     */
    public fun metrics(block: MutableMeta.() -> Unit) {
        this.metrics.apply(block)
    }

    /**
     * Configures static labels for any metrics generated for this property.
     *
     * **WARNING:** Avoid using labels with high cardinality (e.g., user IDs, session IDs, request IDs).
     * Each unique combination of labels creates a new time series in Prometheus, which can lead to
     * significant memory consumption and performance degradation. Use labels for a limited,
     * well-defined set of categories.
     *
     * Example:
     * ```
     * labels {
     *     put("device_name", "motor1")
     *     put("axis", "X")
     * }
     * ```
     */
    public fun labels(block: MutableMap<String, String>.() -> Unit) {
        this.labels.apply(block)
    }


    /**
     * Adds a required permission to access this property.
     */
    public fun requires(permission: Permission) {
        permissions.add(permission)
    }

    /**
     * Adds a required permission by its string identifier.
     */
    public fun requires(permissionId: String) {
        requires(Permission(permissionId))
    }

    /**
     * Configures required permissions for accessing this property in a structured way.
     *
     * Example:
     * ```     * permissions {
     *     read = "mydevice.property.read"
     *     write = "mydevice.property.write"
     * }
     * ```
     */
    public fun permissions(block: PermissionBuilder.() -> Unit) {
        permissions.addAll(PermissionBuilder().apply(block).permissions)
    }

    /**
     * Builds the final, immutable [PropertyDescriptor]. This method is public to be accessible from public inline functions.
     */
    public fun build(mutable: Boolean, valueTypeName: String): PropertyDescriptor {
        val finalKind = kind ?: error(
            "PropertyKind for property '$name' must be assigned automatically by the DSL delegate. " +
                    "This indicates an internal error in the framework or improper use of the builder."
        )

        val attributes = mutableSetOf<MemberAttribute>()

        attributes.add(
            MetadataAttribute(
                description = description,
                help = help,
                group = group,
                icon = icon,
                unit = unit,
                widgetHint = widgetHint,
                tags = tags
            )
        )

        // Behavior
        if (timeout != null || _requiredLocks.isNotEmpty()) {
            attributes.add(BehaviorAttribute(timeout = timeout, requiredLocks = _requiredLocks.toList()))
        }

        // Access
        attributes.add(
            AccessAttribute(
                readable = readable,
                mutable = mutable,
                readPermissions = permissions, // TODO Simplified mapping, split read/write
                writePermissions = if (mutable) permissions else emptySet()
            )
        )

        // Persistence
        if (persistent || transient) {
            attributes.add(PersistenceAttribute(persistent = persistent, transient = transient))
        }

        // Validation
        if (validationRules.isNotEmpty() || allowedValues != null || range != null) {
            attributes.add(
                ValidationAttribute(
                    rules = validationRules.toList(),
//                    allowedValues = allowedValues,
//                    rangeMin = range?.start,
//                    rangeMax = range?.endInclusive
                )
            )
        }

        // Telemetry
        if (!metrics.isEmpty() || labels.isNotEmpty()) {
            attributes.add(TelemetryAttribute(metrics = metrics.seal(), labels = labels.toMap()))
        }

        // Bindings
        if (_bindings.isNotEmpty()) {
            attributes.add(BindingsAttribute(bindings = _bindings.toMap()))
        }

        return PropertyDescriptor(
            name = name,
            kind = finalKind,
            valueTypeName = valueTypeName,
            metaDescriptor = metaDescriptor,
            attributes = attributes
        )
    }
}

/**
 * A mutable builder for creating [ActionDescriptor] instances in a DSL.
 */
@CompositeSpecDsl
public class ActionDescriptorBuilder(public val name: Name) {
    public var description: String? = null
    public var help: String? = null
    public var group: String? = null
    public var icon: String? = null

    /**
     * A recommended timeout for a single execution of this action's logic.
     * A client or a runtime may use this hint but can override it.
     * This timeout typically covers only the core execution, not time spent in queues.
     */
    public var defaultTimeout: Duration? = null

    /**
     * A hard deadline for the total duration of the action's execution, starting from the moment it is dispatched.
     * This includes any time spent in queues. The runtime is expected to enforce this as a strict limit
     * that cannot be overridden by clients. If the deadline is exceeded, the action must be considered failed.
     */
    public var executionDeadline: Duration? = null

    @PublishedApi
    internal val _requiredLocks: MutableList<ResourceLockSpec> = mutableListOf()

    public var meta: MutableMeta = MutableMeta()
    public var inputMetaDescriptor: MetaDescriptor = MetaDescriptor()
    private var outputSpecInstance: ActionOutputSpec? = null

    /**
     * Configures the output structure of this action in a type-safe way.
     * @param spec An instance of a class derived from [ActionOutputSpec].
     */
    @OptIn(DFExperimental::class)
    public fun output(spec: ActionOutputSpec) {
        this.outputSpecInstance = spec
    }

    /**
     * Configures the output structure of this action using a DSL block.
     * @param block A lambda with an anonymous [ActionOutputSpec] as its receiver.
     */
    @OptIn(DFExperimental::class)
    public fun output(block: ActionOutputSpec.() -> Unit) {
        this.outputSpecInstance = object : ActionOutputSpec() {}.apply(block)
    }


    @PublishedApi
    internal val possibleFaults: MutableSet<String> = mutableSetOf()

    private val _tags = mutableSetOf<MemberTag>()
    /**
     * A read-only view of the semantic tags added to this action.
     */
    public val tags: Set<MemberTag> get() = _tags

    private val _bindings = mutableMapOf<String, AdapterBinding>()
    /**
     * A read-only view of the protocol-specific bindings attached to this action.
     */
    public val bindings: Map<String, AdapterBinding> get() = _bindings

    /**
     * Adds a semantic [MemberTag] to this action.
     */
    public fun addTag(tag: MemberTag) {
        _tags.add(tag)
    }

    /**
     * Adds a protocol or system-specific alias for this action.
     * @param namespace A string that uniquely identifies the context for this alias.
     * @param alias The alternative name or identifier within that namespace.
     */
    public fun alias(namespace: String, alias: String) {
        addTag(AliasTag(namespace, alias))
    }

    /**
     * Attaches a type-safe, protocol-specific configuration block ([AdapterBinding]) to this action.
     */
    public fun attachBinding(key: String, binding: AdapterBinding) {
        _bindings[key] = binding
    }

    /**
     * Declares that this action can result in a specific, predictable business fault.
     */
    public inline fun <reified F : DeviceFault> canFault() {
        possibleFaults.add(serializer<F>().descriptor.serialName)
    }

    public var logicId: Name? = null
    public var logicVersionConstraint: String? = null
    public var taskBlueprintId: String? = null
    public var distributable: Boolean = false
    public val permissions: MutableSet<Permission> = mutableSetOf()
    public val metrics: MutableMeta = MutableMeta()
    public val labels: MutableMap<String, String> = mutableMapOf()

    @PublishedApi
    internal var cachePolicy: CachePolicy? = null

    @PublishedApi
    internal var operationalEventTypeName: String? = null

    @PublishedApi
    internal var operationalEventMeta: Meta? = null

    @PublishedApi
    internal var operationalSuccessEventTypeName: String? = null

    @PublishedApi
    internal var operationalSuccessEventMeta: Meta? = null

    @PublishedApi
    internal var operationalFailureEventTypeName: String? = null

    @PublishedApi
    internal var operationalFailureEventMeta: Meta? = null

    @PublishedApi
    internal var taskInputTypeName: String? = null

    @PublishedApi
    internal var taskOutputTypeName: String? = null

    @PublishedApi
    internal val _requiredPredicates: MutableSet<Name> = mutableSetOf()

    public fun requires(predicate: DevicePropertySpec<*, Boolean>) {
        require(predicate.descriptor.kind == PropertyKind.PREDICATE) {
            "The property '${predicate.name}' used as a requirement must be a PREDICATE. It is currently of kind '${predicate.descriptor.kind}'."
        }
        _requiredPredicates.add(predicate.name)
    }

    public fun requiresLock(resourceName: Name, type: LockType = LockType.EXCLUSIVE_WRITE) {
        _requiredLocks.add(ResourceLockSpec(resourceName, type))
    }

    public fun requiresLock(resourceName: String, type: LockType = LockType.EXCLUSIVE_WRITE): Unit =
        requiresLock(Name.parse(resourceName), type)

    /**
     * Configures arbitrary metadata for this action using a raw [MutableMeta] block.
     * Useful for storing additional information, such as a serialized transaction plan.
     */
    public fun meta(block: MutableMeta.() -> Unit) {
        this.meta.apply(block)
    }

    /**
     * Configures arbitrary metadata for this action using a type-safe [Scheme].
     * @param scheme An instance of a [Scheme] containing the configuration.
     */
    public fun meta(scheme: Scheme) {
        this.meta.update(scheme.toMeta())
    }

    /**
     * Configures arbitrary metadata for this action using a type-safe [SchemeSpec] and a configuration block.
     * @param spec The [SchemeSpec] companion object for your configuration scheme.
     * @param block A type-safe lambda to configure the scheme.
     */
    public fun <S : Scheme> meta(spec: SchemeSpec<S>, block: S.() -> Unit) {
        meta(spec(block))
    }

    public fun cacheable(
        ttl: Duration,
        scope: CacheScope = CacheScope.PER_HUB,
        vararg invalidationEvents: Name
    ) {
        this.cachePolicy = CachePolicy(ttl, scope, invalidationEvents.toList())
    }

    /**
     * Adds metadata to this action's descriptor to indicate that its invocations should be counted.
     * The runtime can use this hint to automatically create and update a counter metric.
     * @param name The name of the counter metric. Defaults to "[action_name]_invocations_total".
     */
    public fun countInvocations(name: String = "${this.name}_invocations_total") {
        metrics {
            "metric.counter.$name.enabled" put true
        }
    }

    /**
     * Adds metadata to this action's descriptor to indicate that its execution time should be measured.
     * The runtime can use this hint to automatically create and update a histogram metric.
     * @param name The name of the histogram metric. Defaults to "[action_name]_execution_duration_seconds".
     * @param buckets An optional list of upper bounds for the histogram buckets, in seconds. If not provided,
     *                a default set of buckets will be used by the runtime.
     */
    public fun measureExecutionTime(
        name: String = "${this.name}_execution_duration_seconds",
        buckets: List<Double>? = null
    ) {
        metrics {
            "metric.histogram.$name.enabled" put true
            buckets?.let {
                "metric.histogram.$name.buckets" put it.map { b -> b.asValue() }.asValue()
            }
        }
    }

    /**
     * Links this action to an event in the operational FSM. When the action is executed,
     * the specified event will be posted to the FSM *before* the action's logic runs.
     *
     * @param E The type of the event to trigger. Must be a `@Serializable` class that implements [Event].
     * @param argsBuilder A lambda to build a [Meta] object that will be used by the runtime
     *                    to construct the event instance.
     */
    public inline fun <reified E : Event> triggers(crossinline argsBuilder: MutableMeta.() -> Unit = {}) {
        this.operationalEventTypeName = serializer<E>().descriptor.serialName
        this.operationalEventMeta = MutableMeta().apply(argsBuilder).seal()
    }

    /**
     * Links this action to an event that should be posted on successful completion of the action.
     * The runtime will post this event after the action's `execute` block completes without an exception.
     *
     * @param E The type of the success event.
     * @param argsBuilder A lambda to build the [Meta] for constructing the event.
     */
    public inline fun <reified E : Event> triggersOnSuccess(crossinline argsBuilder: MutableMeta.() -> Unit = {}) {
        this.operationalSuccessEventTypeName = serializer<E>().descriptor.serialName
        this.operationalSuccessEventMeta = MutableMeta().apply(argsBuilder).seal()
    }

    /**
     * Links this action to an event that should be posted on failure of the action.
     * The runtime will post this event if the action's `execute` block throws an exception.
     *
     * @param E The type of the failure event.
     * @param argsBuilder A lambda to build the [Meta] for constructing the event.
     */
    public inline fun <reified E : Event> triggersOnFailure(crossinline argsBuilder: MutableMeta.() -> Unit = {}) {
        this.operationalFailureEventTypeName = serializer<E>().descriptor.serialName
        this.operationalFailureEventMeta = MutableMeta().apply(argsBuilder).seal()
    }

    /**
     * Configures the metadata descriptor for the action's input arguments using a raw builder block.
     */
    public fun inputMeta(block: MetaDescriptorBuilder.() -> Unit) {
        this.inputMetaDescriptor = MetaDescriptor(block)
    }

    /**
     * Configures the metadata descriptor for the action's input arguments using a type-safe [SchemeSpec].
     *
     * @param spec The [SchemeSpec] companion object for your input configuration scheme.
     * @param block An optional lambda to further customize the generated [MetaDescriptor].
     */
    public fun <S : Scheme> inputMeta(spec: SchemeSpec<S>, block: MetaDescriptorBuilder.() -> Unit = {}) {
        this.inputMetaDescriptor = MetaDescriptor {
            spec.descriptor?.let { from(it) }
            block()
        }
    }


    /**
     * Configures metrics for this action.
     * Example:
     * ```
     * metrics {
     *     "counter.invocations.enabled" put true
     *     "histogram.duration_seconds.enabled" put true
     * }
     * ```
     */
    public fun metrics(block: MutableMeta.() -> Unit) {
        this.metrics.apply(block)
    }

    /**
     * Configures static labels for any metrics generated for this action.
     * **WARNING:** See the warning about high cardinality in [PropertyDescriptorBuilder.labels].
     */
    public fun labels(block: MutableMap<String, String>.() -> Unit) {
        this.labels.apply(block)
    }


    /**
     * Adds a required permission to execute this action.
     */
    public fun requires(permission: Permission) {
        permissions.add(permission)
    }

    /**
     * Adds a required permission by its string identifier.
     */
    public fun requires(permissionId: String) {
        requires(Permission(permissionId))
    }

    /**
     * Configures required permissions for executing this action.
     */
    public fun permissions(block: PermissionBuilder.() -> Unit) {
        permissions.addAll(PermissionBuilder().apply(block).permissions)
    }

    /**
     * Builds the final, immutable [ActionDescriptor]. This method is public to be accessible from public inline functions.
     */
    @OptIn(DFExperimental::class)
    public fun build(): ActionDescriptor {
        val attributes = mutableSetOf<MemberAttribute>()

        // Metadata
        attributes.add(MetadataAttribute(
            description = description,
            help = help,
            group = group,
            icon = icon,
            tags = tags
        ))

        // Behavior
        if (defaultTimeout != null || executionDeadline != null || _requiredLocks.isNotEmpty() || cachePolicy != null) {
            attributes.add(BehaviorAttribute(
                timeout = defaultTimeout,
                executionDeadline = executionDeadline,
                requiredLocks = _requiredLocks.toList(),
                cachePolicy = cachePolicy
            ))
        }

        // Implementation
        if (logicId != null || taskBlueprintId != null || !meta.isEmpty()) {
            attributes.add(
                ImplementationAttribute(
                    logicId = logicId,
                    logicVersionConstraint = logicVersionConstraint,
                    taskBlueprintId = taskBlueprintId,
                    distributable = distributable,
                    taskInputTypeName = taskInputTypeName,
                    taskOutputTypeName = taskOutputTypeName,
                    executionMeta = meta.seal()
                )
            )
        }

        // Access
        if (permissions.isNotEmpty()) {
            attributes.add(AccessAttribute(
                readPermissions = permissions, // TODO same permissions
                writePermissions = permissions
            ))
        }

        // Telemetry
        if (!metrics.isEmpty() || labels.isNotEmpty()) {
            attributes.add(TelemetryAttribute(metrics = metrics.seal(), labels = labels.toMap()))
        }

        // FSM
        if (operationalEventTypeName != null || _requiredPredicates.isNotEmpty() || possibleFaults.isNotEmpty()) {
            attributes.add(
                FsmAttribute(
                    operationalEventTypeName = operationalEventTypeName,
                    operationalEventMeta = operationalEventMeta,
                    operationalSuccessEventTypeName = operationalSuccessEventTypeName,
                    operationalSuccessEventMeta = operationalSuccessEventMeta,
                    operationalFailureEventTypeName = operationalFailureEventTypeName,
                    operationalFailureEventMeta = operationalFailureEventMeta,
                    possibleFaults = possibleFaults,
                    requiredPredicates = _requiredPredicates
                )
            )
        }

        // Bindings
        if (_bindings.isNotEmpty()) {
            attributes.add(BindingsAttribute(bindings = _bindings.toMap()))
        }

        return ActionDescriptor(
            name = name,
            inputMetaDescriptor = inputMetaDescriptor,
            outputDescriptor = outputSpecInstance?.descriptor ?: MetaDescriptor(),
            attributes = attributes
        )
    }
}