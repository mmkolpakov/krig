package space.kscience.krig.ui.schema

import kotlin.jvm.JvmInline
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.descriptors.MetaDescriptor
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName
import space.kscience.dataforge.names.parseAsName
import space.kscience.krig.api.data.DataQuality
import space.kscience.krig.api.data.ObservedValue
import space.kscience.krig.api.descriptors.ActionDescriptor
import space.kscience.krig.api.descriptors.OperationDescriptor
import space.kscience.krig.api.descriptors.PropertyDescriptor
import space.kscience.krig.api.descriptors.PropertyKind
import space.kscience.krig.api.descriptors.TypeId
import space.kscience.krig.api.descriptors.TypeIds
import space.kscience.krig.api.descriptors.attributes.AcquisitionPolicyAttribute
import space.kscience.krig.api.descriptors.attributes.BehaviorAttribute
import space.kscience.krig.api.descriptors.attributes.DeliveryClassAttribute
import space.kscience.krig.api.descriptors.attributes.EngineeringRangeAttribute
import space.kscience.krig.api.descriptors.attributes.PhysicalQuantityAttribute
import space.kscience.krig.api.descriptors.attributes.TaskAttribute
import space.kscience.krig.api.descriptors.attributes.VirtualPropertyAttribute
import space.kscience.krig.api.descriptors.attributes.acquisitionPolicy
import space.kscience.krig.api.descriptors.attributes.behavior
import space.kscience.krig.api.descriptors.attributes.bindings
import space.kscience.krig.api.descriptors.attributes.deliveryClass
import space.kscience.krig.api.descriptors.attributes.description
import space.kscience.krig.api.descriptors.attributes.engineeringRange
import space.kscience.krig.api.descriptors.attributes.messageDeliveryClass
import space.kscience.krig.api.descriptors.attributes.mutable
import space.kscience.krig.api.descriptors.attributes.physicalQuantity
import space.kscience.krig.api.descriptors.attributes.readable
import space.kscience.krig.api.descriptors.attributes.task
import space.kscience.krig.api.descriptors.attributes.unit
import space.kscience.krig.api.descriptors.attributes.virtualProperty
import space.kscience.krig.core.contracts.Device
import space.kscience.krig.core.contracts.DeviceManifest
import space.kscience.krig.core.contracts.DynamicDescriptorOverlay
import space.kscience.krig.core.contracts.DynamicDiscoveryPolicy
import space.kscience.krig.core.contracts.schemaHash
import space.kscience.krig.core.contracts.snapshotManifest

public const val DEVICE_FORM_SCHEMA_VERSION: Int = 1

/**
 * Stable renderer-neutral form model projected from a device manifest or live device snapshot.
 *
 * The canonical shape of property values and action payloads is [MetaDescriptor]. JSON Schema,
 * OpenAPI and renderer-native documents are derived projections at transport or renderer boundaries.
 */
@Serializable
@SerialName("schema.device.form")
public data class DeviceFormSchema(
    public val schemaVersion: Int = DEVICE_FORM_SCHEMA_VERSION,
    public val manifestId: Name,
    public val manifestVersion: String,
    public val deviceContractFqName: String,
    public val schemaHash: String,
    public val rendererProfile: DeviceFormRendererProfile,
    public val sections: List<DeviceFormSection>,
    public val properties: List<DeviceFormProperty>,
    public val actions: List<DeviceFormAction>,
    public val discoveredProperties: List<DeviceFormProperty> = emptyList(),
    public val commands: List<DeviceFormCommand> = emptyList(),
    public val bindings: List<DeviceFormBinding> = emptyList(),
    public val dynamicDiscoveryPolicy: DynamicDiscoveryPolicy? = null,
    public val debugTrace: List<DeviceFormDebugTrace> = emptyList(),
)

@Serializable
@JvmInline
public value class DeviceFormNodeId(public val name: Name) {
    override fun toString(): String = name.toString()
}

@Serializable
public enum class DeviceFormPropertyOrigin {
    Manifest,
    Discovered,
}

@Serializable
public enum class DeviceFormSourceKind {
    ManifestProperty,
    DiscoveredProperty,
    Action,
    TaskStateProperty,
    TaskProgressProperty,
    TaskCancelAction,
}

@Serializable
public enum class DeviceFormControlKind {
    Display,
    NumericInput,
    TextInput,
    BooleanInput,
    BinaryInput,
    ObjectEditor,
    CommandButton,
    TaskLink,
}

@Serializable
public enum class DeviceFormBindingKind {
    PropertyValue,
    PropertyQuality,
    ActionCommand,
    TaskState,
    TaskProgress,
}

@Serializable
public enum class DeviceFormCommandKind {
    ReadProperty,
    WriteProperty,
    ExecuteAction,
    SubscribeProperty,
    OpenTaskState,
    CancelTask,
}

@Serializable
public enum class DeviceFormValidationMode {
    None,
    LocalDescriptor,
    HostValidated,
}

@Serializable
public enum class DeviceFormCapability {
    Read,
    Write,
    Execute,
    Subscribe,
    Quality,
    Tasks,
    DynamicDiscovery,
}

@Serializable
public enum class DeviceFormRenderHint {
    Advanced,
    Critical,
    Dynamic,
    ReadOnly,
    Task,
    Virtual,
}

@Serializable
@SerialName("schema.device.form.renderer-profile")
public data class DeviceFormRendererProfile(
    public val capabilities: List<DeviceFormCapability> = emptyList(),
    public val hints: List<DeviceFormRenderHint> = emptyList(),
)

@Serializable
@SerialName("schema.device.form.section")
public data class DeviceFormSection(
    public val id: DeviceFormNodeId,
    public val label: String,
    public val nodes: List<DeviceFormNodeId>,
)

@Serializable
@SerialName("schema.device.form.source")
public data class DeviceFormSourceReference(
    public val kind: DeviceFormSourceKind,
    public val name: Name,
)

@Serializable
@SerialName("schema.device.form.binding")
public data class DeviceFormBinding(
    public val id: DeviceFormNodeId,
    public val kind: DeviceFormBindingKind,
    public val source: DeviceFormSourceReference,
)

@Serializable
@SerialName("schema.device.form.command")
public data class DeviceFormCommand(
    public val id: DeviceFormNodeId,
    public val kind: DeviceFormCommandKind,
    public val target: DeviceFormSourceReference,
    public val inputDescriptor: MetaDescriptor = MetaDescriptor(),
    public val outputDescriptor: MetaDescriptor = MetaDescriptor(),
    public val validationMode: DeviceFormValidationMode = DeviceFormValidationMode.HostValidated,
    public val task: DeviceFormTaskReference? = null,
)

@Serializable
@SerialName("schema.device.form.command-envelope")
public data class DeviceFormCommandEnvelope(
    public val commandId: DeviceFormNodeId,
    public val input: Meta? = null,
    public val correlationId: String? = null,
)

@Serializable
@SerialName("schema.device.form.task")
public data class DeviceFormTaskReference(
    public val stateProperty: Name? = null,
    public val progressProperty: Name? = null,
    public val cancelAction: Name? = null,
    public val emitsMessages: Boolean = true,
)

@Serializable
@SerialName("schema.device.form.descriptor-semantics")
public data class DeviceFormDescriptorSemantics(
    public val engineeringRange: EngineeringRangeAttribute? = null,
    public val acquisitionPolicy: AcquisitionPolicyAttribute? = null,
    public val deliveryClass: DeliveryClassAttribute? = null,
    public val physicalQuantity: PhysicalQuantityAttribute? = null,
    public val behavior: BehaviorAttribute? = null,
    public val virtualProperty: VirtualPropertyAttribute? = null,
    public val protocolBindings: Map<String, Meta> = emptyMap(),
)

@Serializable
@SerialName("schema.device.form.property")
public data class DeviceFormProperty(
    public val id: DeviceFormNodeId,
    public val name: Name,
    public val kind: PropertyKind,
    public val valueTypeId: TypeId,
    public val readable: Boolean,
    public val mutable: Boolean,
    public val description: String? = null,
    public val unit: String? = null,
    public val valueDescriptor: MetaDescriptor,
    public val origin: DeviceFormPropertyOrigin = DeviceFormPropertyOrigin.Manifest,
    public val source: DeviceFormSourceReference,
    public val valueBinding: DeviceFormBinding? = null,
    public val qualityBinding: DeviceFormBinding? = null,
    public val controlKind: DeviceFormControlKind,
    public val validationMode: DeviceFormValidationMode = DeviceFormValidationMode.LocalDescriptor,
    public val semantics: DeviceFormDescriptorSemantics = DeviceFormDescriptorSemantics(),
    public val renderHints: List<DeviceFormRenderHint> = emptyList(),
)

@Serializable
@SerialName("schema.device.form.action")
public data class DeviceFormAction(
    public val id: DeviceFormNodeId,
    public val name: Name,
    public val description: String? = null,
    public val inputDescriptor: MetaDescriptor,
    public val outputDescriptor: MetaDescriptor,
    public val source: DeviceFormSourceReference,
    public val command: DeviceFormCommand,
    public val task: DeviceFormTaskReference? = null,
    public val controlKind: DeviceFormControlKind = DeviceFormControlKind.CommandButton,
    public val validationMode: DeviceFormValidationMode = DeviceFormValidationMode.HostValidated,
    public val semantics: DeviceFormDescriptorSemantics = DeviceFormDescriptorSemantics(),
    public val renderHints: List<DeviceFormRenderHint> = emptyList(),
)

@Serializable
@SerialName("schema.device.form.quality")
public data class DeviceFormQuality(
    public val severity: Int,
    public val label: String,
    public val code: String? = null,
    public val detail: String? = null,
)

@Serializable
@SerialName("schema.device.form.observed-meta")
public data class DeviceFormObservedMeta(
    public val value: Meta? = null,
    public val time: String,
    public val quality: DeviceFormQuality,
)

@Serializable
@SerialName("schema.device.form.state-snapshot")
public data class DeviceFormStateSnapshot(
    public val values: Map<Name, DeviceFormObservedMeta> = emptyMap(),
)

@Serializable
@SerialName("schema.device.form.state-patch")
public data class DeviceFormStatePatch(
    public val updates: Map<Name, DeviceFormObservedMeta> = emptyMap(),
    public val removed: Set<Name> = emptySet(),
)

@Serializable
@SerialName("schema.device.form.debug-trace")
public data class DeviceFormDebugTrace(
    public val nodeId: DeviceFormNodeId,
    public val source: DeviceFormSourceReference,
    public val notes: List<String> = emptyList(),
)

public fun DeviceManifest.toDeviceFormSchema(
    discoveredProperties: Iterable<PropertyDescriptor> = emptyList(),
    dynamicDiscoveryPolicy: DynamicDiscoveryPolicy? = null,
): DeviceFormSchema {
    val manifestProperties = properties.values
        .sortedBy { it.name.toString() }
        .map { it.toDeviceFormProperty(DeviceFormPropertyOrigin.Manifest) }
    val discovered = discoveredProperties
        .sortedBy { it.name.toString() }
        .map { it.toDeviceFormProperty(DeviceFormPropertyOrigin.Discovered) }
    val formActions = actions.values
        .sortedBy { it.name.toString() }
        .map { it.toDeviceFormAction() }
    val allProperties = manifestProperties + discovered
    val commands = allProperties.flatMap(DeviceFormProperty::propertyCommands) + formActions.flatMap { it.actionCommands() }
    val bindings = allProperties.flatMap(DeviceFormProperty::propertyBindings) +
            formActions.flatMap { it.actionBindings() }

    return DeviceFormSchema(
        manifestId = id,
        manifestVersion = version,
        deviceContractFqName = deviceContractFqName,
        schemaHash = schemaHash(),
        rendererProfile = formRendererProfile(allProperties, formActions, dynamicDiscoveryPolicy),
        sections = formSections(manifestProperties, discovered, formActions),
        properties = manifestProperties,
        actions = formActions,
        discoveredProperties = discovered,
        commands = commands,
        bindings = bindings,
        dynamicDiscoveryPolicy = dynamicDiscoveryPolicy,
        debugTrace = allProperties.map { it.debugTrace() } + formActions.map { it.debugTrace() },
    )
}

public fun Device.snapshotDeviceFormSchema(
    id: Name = name,
    version: String = "runtime",
): DeviceFormSchema {
    val overlay = this as? DynamicDescriptorOverlay
    return snapshotManifest(id = id, version = version).toDeviceFormSchema(
        discoveredProperties = overlay?.discoveredPropertyDescriptors?.values.orEmpty(),
        dynamicDiscoveryPolicy = overlay?.dynamicDiscoveryPolicy,
    )
}

public fun PropertyDescriptor.toDeviceFormProperty(
    origin: DeviceFormPropertyOrigin = DeviceFormPropertyOrigin.Manifest,
): DeviceFormProperty {
    val source = sourceReference(origin)
    return DeviceFormProperty(
        id = nodeId(if (origin == DeviceFormPropertyOrigin.Manifest) "property" else "discovered", name),
        name = name,
        kind = kind,
        valueTypeId = valueTypeId,
        readable = readable,
        mutable = mutable,
        description = description,
        unit = unit,
        valueDescriptor = metaDescriptor,
        origin = origin,
        source = source,
        valueBinding = if (readable) valueBinding(source) else null,
        qualityBinding = if (readable) qualityBinding(source) else null,
        controlKind = valueTypeId.formControlKind(mutable),
        semantics = descriptorSemantics(),
        renderHints = renderHints(origin),
    )
}

public fun ActionDescriptor.toDeviceFormAction(): DeviceFormAction {
    val source = DeviceFormSourceReference(DeviceFormSourceKind.Action, name)
    val taskReference = task?.toDeviceFormTaskReference()
    val command = DeviceFormCommand(
        id = nodeId("command.action", name),
        kind = DeviceFormCommandKind.ExecuteAction,
        target = source,
        inputDescriptor = inputMetaDescriptor,
        outputDescriptor = outputMetaDescriptor,
        task = taskReference,
    )
    return DeviceFormAction(
        id = nodeId("action", name),
        name = name,
        description = description,
        inputDescriptor = inputMetaDescriptor,
        outputDescriptor = outputMetaDescriptor,
        source = source,
        command = command,
        task = taskReference,
        controlKind = if (taskReference == null) DeviceFormControlKind.CommandButton else DeviceFormControlKind.TaskLink,
        semantics = descriptorSemantics(),
        renderHints = if (taskReference == null) emptyList() else listOf(DeviceFormRenderHint.Task),
    )
}

public fun DataQuality.toDeviceFormQuality(): DeviceFormQuality = DeviceFormQuality(
    severity = severity.rank,
    label = severity.label,
    code = code?.id,
    detail = detail,
)

public fun ObservedValue<Meta?>.toDeviceFormObservedMeta(): DeviceFormObservedMeta = DeviceFormObservedMeta(
    value = value,
    time = time.toString(),
    quality = quality.toDeviceFormQuality(),
)

private fun PropertyDescriptor.sourceReference(origin: DeviceFormPropertyOrigin): DeviceFormSourceReference =
    DeviceFormSourceReference(
        kind = when (origin) {
            DeviceFormPropertyOrigin.Manifest -> DeviceFormSourceKind.ManifestProperty
            DeviceFormPropertyOrigin.Discovered -> DeviceFormSourceKind.DiscoveredProperty
        },
        name = name,
    )

private fun PropertyDescriptor.valueBinding(source: DeviceFormSourceReference): DeviceFormBinding = DeviceFormBinding(
    id = nodeId("binding.value", name),
    kind = DeviceFormBindingKind.PropertyValue,
    source = source,
)

private fun PropertyDescriptor.qualityBinding(source: DeviceFormSourceReference): DeviceFormBinding = DeviceFormBinding(
    id = nodeId("binding.quality", name),
    kind = DeviceFormBindingKind.PropertyQuality,
    source = source,
)

private fun DeviceFormProperty.propertyCommands(): List<DeviceFormCommand> = buildList {
    if (readable) {
        add(
            DeviceFormCommand(
                id = nodeId("command.read", name),
                kind = DeviceFormCommandKind.ReadProperty,
                target = source,
                outputDescriptor = valueDescriptor,
            ),
        )
        add(
            DeviceFormCommand(
                id = nodeId("command.subscribe", name),
                kind = DeviceFormCommandKind.SubscribeProperty,
                target = source,
                outputDescriptor = valueDescriptor,
            ),
        )
    }
    if (mutable) {
        add(
            DeviceFormCommand(
                id = nodeId("command.write", name),
                kind = DeviceFormCommandKind.WriteProperty,
                target = source,
                inputDescriptor = valueDescriptor,
            ),
        )
    }
}

private fun DeviceFormProperty.propertyBindings(): List<DeviceFormBinding> = listOfNotNull(valueBinding, qualityBinding)

private fun DeviceFormAction.actionCommands(): List<DeviceFormCommand> = buildList {
    add(command)
    val taskReference = task ?: return@buildList
    taskReference.stateProperty?.let { stateProperty ->
        add(
            DeviceFormCommand(
                id = nodeId("command.task-state", stateProperty),
                kind = DeviceFormCommandKind.OpenTaskState,
                target = DeviceFormSourceReference(DeviceFormSourceKind.TaskStateProperty, stateProperty),
            ),
        )
    }
    taskReference.cancelAction?.let { cancelAction ->
        add(
            DeviceFormCommand(
                id = nodeId("command.cancel-task", cancelAction),
                kind = DeviceFormCommandKind.CancelTask,
                target = DeviceFormSourceReference(DeviceFormSourceKind.TaskCancelAction, cancelAction),
            ),
        )
    }
}

private fun DeviceFormAction.actionBindings(): List<DeviceFormBinding> = buildList {
    add(
        DeviceFormBinding(
            id = nodeId("binding.command", name),
            kind = DeviceFormBindingKind.ActionCommand,
            source = source,
        ),
    )
    val taskReference = task ?: return@buildList
    taskReference.stateProperty?.let { stateProperty ->
        add(
            DeviceFormBinding(
                id = nodeId("binding.task-state", stateProperty),
                kind = DeviceFormBindingKind.TaskState,
                source = DeviceFormSourceReference(DeviceFormSourceKind.TaskStateProperty, stateProperty),
            ),
        )
    }
    taskReference.progressProperty?.let { progressProperty ->
        add(
            DeviceFormBinding(
                id = nodeId("binding.task-progress", progressProperty),
                kind = DeviceFormBindingKind.TaskProgress,
                source = DeviceFormSourceReference(DeviceFormSourceKind.TaskProgressProperty, progressProperty),
            ),
        )
    }
}

private fun DeviceFormProperty.debugTrace(): DeviceFormDebugTrace = DeviceFormDebugTrace(
    nodeId = id,
    source = source,
    notes = listOf("descriptor:${origin.name.lowercase()}", "type:$valueTypeId"),
)

private fun DeviceFormAction.debugTrace(): DeviceFormDebugTrace = DeviceFormDebugTrace(
    nodeId = id,
    source = source,
    notes = listOf("descriptor:action"),
)

private fun OperationDescriptor.descriptorSemantics(): DeviceFormDescriptorSemantics = DeviceFormDescriptorSemantics(
    engineeringRange = engineeringRange,
    acquisitionPolicy = acquisitionPolicy,
    deliveryClass = deliveryClass,
    physicalQuantity = physicalQuantity,
    behavior = behavior,
    virtualProperty = virtualProperty,
    protocolBindings = bindings,
)

private fun PropertyDescriptor.renderHints(origin: DeviceFormPropertyOrigin): List<DeviceFormRenderHint> = buildList {
    if (!mutable) add(DeviceFormRenderHint.ReadOnly)
    if (origin == DeviceFormPropertyOrigin.Discovered) add(DeviceFormRenderHint.Dynamic)
    if (messageDeliveryClass.name.contains("Critical") || messageDeliveryClass.name == "Safety") {
        add(DeviceFormRenderHint.Critical)
    }
    if (virtualProperty != null) add(DeviceFormRenderHint.Virtual)
}.distinct()

private fun TypeId.formControlKind(mutable: Boolean): DeviceFormControlKind {
    if (!mutable) return DeviceFormControlKind.Display
    return when (this) {
        TypeIds.DOUBLE, TypeIds.INT, TypeIds.LONG -> DeviceFormControlKind.NumericInput
        TypeIds.BOOLEAN -> DeviceFormControlKind.BooleanInput
        TypeIds.STRING -> DeviceFormControlKind.TextInput
        TypeIds.BYTES -> DeviceFormControlKind.BinaryInput
        else -> DeviceFormControlKind.ObjectEditor
    }
}

private fun TaskAttribute.toDeviceFormTaskReference(): DeviceFormTaskReference = DeviceFormTaskReference(
    stateProperty = stateProperty,
    progressProperty = progressProperty,
    cancelAction = cancelAction,
    emitsMessages = emitsMessages,
)

private fun formRendererProfile(
    properties: List<DeviceFormProperty>,
    actions: List<DeviceFormAction>,
    dynamicDiscoveryPolicy: DynamicDiscoveryPolicy?,
): DeviceFormRendererProfile {
    val capabilities = buildList {
        if (properties.any { it.readable }) add(DeviceFormCapability.Read)
        if (properties.any { it.mutable }) add(DeviceFormCapability.Write)
        if (actions.isNotEmpty()) add(DeviceFormCapability.Execute)
        if (properties.any { it.readable }) {
            add(DeviceFormCapability.Subscribe)
            add(DeviceFormCapability.Quality)
        }
        if (actions.any { it.task != null }) add(DeviceFormCapability.Tasks)
        if (dynamicDiscoveryPolicy != null && dynamicDiscoveryPolicy != DynamicDiscoveryPolicy.Strict) {
            add(DeviceFormCapability.DynamicDiscovery)
        }
    }.distinct().sortedBy { it.name }
    val hints = (properties.flatMap { it.renderHints } + actions.flatMap { it.renderHints })
        .distinct()
        .sortedBy { it.name }
    return DeviceFormRendererProfile(capabilities = capabilities, hints = hints)
}

private fun formSections(
    properties: List<DeviceFormProperty>,
    discoveredProperties: List<DeviceFormProperty>,
    actions: List<DeviceFormAction>,
): List<DeviceFormSection> = buildList {
    if (properties.isNotEmpty()) {
        add(DeviceFormSection(sectionId("properties"), "properties", properties.map { it.id }))
    }
    if (discoveredProperties.isNotEmpty()) {
        add(DeviceFormSection(sectionId("discovered"), "discovered", discoveredProperties.map { it.id }))
    }
    if (actions.isNotEmpty()) {
        add(DeviceFormSection(sectionId("actions"), "actions", actions.map { it.id }))
    }
}

private fun sectionId(label: String): DeviceFormNodeId = DeviceFormNodeId(Name("section".asName().tokens + label.asName().tokens))

private fun nodeId(prefix: String, name: Name): DeviceFormNodeId =
    DeviceFormNodeId(Name(prefix.parseAsName().tokens + name.tokens))
