package space.kscience.controls.composite.dsl

import kotlinx.serialization.serializer
import space.kscience.controls.automation.PlanExecutorDevice
import space.kscience.controls.composite.dsl.actions.PlanBuilder
import space.kscience.controls.composite.dsl.actions.plan
import space.kscience.controls.composite.dsl.children.ChildConfigBuilder
import space.kscience.controls.composite.dsl.properties.ActionDescriptorBuilder
import space.kscience.controls.composite.dsl.properties.PropertyDescriptorBuilder
import space.kscience.controls.composite.dsl.streams.StreamDescriptorBuilder
import space.kscience.controls.api.composition.LocalChildComponentConfig
import space.kscience.controls.automation.PlanExecutorFeature
import space.kscience.controls.automation.TaskExecutorDevice
import space.kscience.controls.automation.TaskExecutorFeature
import space.kscience.controls.core.meta.DeviceActionSpec
import space.kscience.controls.core.meta.DevicePropertySpec
import space.kscience.controls.core.meta.DeviceStreamSpec
import space.kscience.controls.core.meta.MutableDevicePropertySpec
import space.kscience.controls.api.descriptors.PropertyDescriptor
import space.kscience.controls.api.descriptors.PropertyKind
import space.kscience.controls.api.descriptors.StreamDescriptor
import space.kscience.controls.common.meta.unit
import space.kscience.controls.connectivity.ChildBindingsFeature
import space.kscience.controls.core.contracts.Device
import space.kscience.controls.core.contracts.DeviceBlueprint
import space.kscience.controls.core.contracts.StreamPort
import space.kscience.dataforge.meta.*
import space.kscience.dataforge.misc.DFExperimental
import space.kscience.dataforge.names.Name
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import kotlin.enums.enumEntries
import kotlin.reflect.KType
import kotlin.reflect.typeOf

private fun valueDescriptor(
    valueType: ValueType,
    userBuilder: PropertyDescriptorBuilder.() -> Unit
): PropertyDescriptorBuilder.() -> Unit = {
    meta { this.valueType(valueType) }
    userBuilder()
}


// --- Child Component DSL ---

/**
 * Declares and registers a single child component for the device directly within a `CompositeSpecBuilder`.
 *
 * @param D The parent device contract type.
 * @param C The child device contract type.
 * @param name The local name for the child component.
 * @param blueprint The blueprint for the child device.
 * @param configBuilder A DSL block to configure the child's lifecycle, meta, and property bindings.
 */
public fun <D : Device, C : Device> CompositeSpecBuilder<D>.child(
    name: Name,
    blueprint: DeviceBlueprint<C>,
    configBuilder: ChildConfigBuilder<D, C>.() -> Unit = {},
) {
    contract {
        callsInPlace(configBuilder, InvocationKind.EXACTLY_ONCE)
    }
    val builder = ChildConfigBuilder<D, C>().apply(configBuilder)

    val childFeatures = builder.features.toMutableSet()
    val bindings = builder.buildBindings()
    if (bindings.isNotEmpty()) {
        childFeatures.add(ChildBindingsFeature(bindings))
    }

    val config = LocalChildComponentConfig(
        blueprintId = blueprint.id,
        blueprintVersion = blueprint.version,
        meta = builder.meta,
    )
    registerChild(name, config)
}

// --- Read-Only Property DSL ---

/**
 * Declares and registers a read-only property directly within a `CompositeSpecBuilder`.
 *
 * @param D The type of the device contract.
 * @param T The type of the property value.
 * @param name The name of the property.
 * @param converter The [MetaConverter] for serialization.
 * @param kind The semantic [PropertyKind] of the property. Defaults to `PHYSICAL`, indicating a direct link to a driver or hardware.
 * @param descriptorBuilder A DSL block to configure the property's descriptor.
 * @param read The suspendable logic for reading the property's value from a device instance.
 * @return The created and registered [DevicePropertySpec].
 */
public inline fun <D : Device, reified T> CompositeSpecBuilder<D>.property(
    name: Name,
    converter: MetaConverter<T>,
    kind: PropertyKind = PropertyKind.PHYSICAL,
    noinline descriptorBuilder: PropertyDescriptorBuilder.() -> Unit = {},
    noinline read: suspend D.() -> T?,
): DevicePropertySpec<D, T> {
    val dslBuilder = PropertyDescriptorBuilder(name).apply{
        this.kind = kind
        descriptorBuilder()
    }
    val descriptor = dslBuilder.build(
        mutable = false,
        valueTypeName = serializer<T>().descriptor.serialName
    )

    val spec = object : DevicePropertySpec<D, T> {
        override val name: Name = name
        override val descriptor: PropertyDescriptor = descriptor
        override val converter: MetaConverter<T> = converter
        override val valueType: KType = typeOf<T>()
        override suspend fun read(device: D): T? = device.read()
    }
    registerProperty(spec)
    return spec
}

// --- Mutable Property DSL ---

/**
 * Declares and registers a mutable property directly within a `CompositeSpecBuilder`.
 *
 * @param D The type of the device contract.
 * @param T The type of the property value.
 * @param name The name of the property.
 * @param converter The [MetaConverter] for serialization.
 * @param kind The semantic [PropertyKind] of the property. Defaults to `PHYSICAL`.
 * @param descriptorBuilder A DSL block to configure the property's descriptor.
 * @param read The suspendable logic for reading the property's value.
 * @param write The suspendable logic for writing a new value.
 * @return The created and registered [MutableDevicePropertySpec].
 */
public inline fun <D : Device, reified T> CompositeSpecBuilder<D>.mutableProperty(
    name: Name,
    converter: MetaConverter<T>,
    kind: PropertyKind = PropertyKind.PHYSICAL,
    noinline descriptorBuilder: PropertyDescriptorBuilder.() -> Unit = {},
    noinline read: suspend D.() -> T?,
    noinline write: suspend D.(value: T) -> Unit,
): MutableDevicePropertySpec<D, T> {
    val dslBuilder = PropertyDescriptorBuilder(name).apply{
        this.kind = kind
        descriptorBuilder()
    }
    val descriptor = dslBuilder.build(
        mutable = true,
        valueTypeName = serializer<T>().descriptor.serialName
    )

    val spec = object : MutableDevicePropertySpec<D, T> {
        override val name: Name = name
        override val descriptor: PropertyDescriptor = descriptor
        override val converter: MetaConverter<T> = converter
        override val valueType: KType = typeOf<T>()
        override suspend fun read(device: D): T? = device.read()
        override suspend fun write(device: D, value: T): Unit = device.write(value)
    }
    registerProperty(spec)
    return spec
}

// --- Action DSL ---

/**
 * Declares and registers a device action directly within a `CompositeSpecBuilder`.
 *
 * @return The created and registered [DeviceActionSpec].
 */
public fun <D : Device, I, O> CompositeSpecBuilder<D>.action(
    name: Name,
    inputConverter: MetaConverter<I>,
    outputConverter: MetaConverter<O>,
    descriptorBuilder: ActionDescriptorBuilder.() -> Unit = {},
    execute: suspend D.(input: I) -> O?,
): DeviceActionSpec<D, I, O> {
    val dslBuilder = ActionDescriptorBuilder(name).apply(descriptorBuilder)
    val descriptor = dslBuilder.build()

    val spec = object : DeviceActionSpec<D, I, O> {
        override val name: Name = name
        override val descriptor = descriptor
        override val inputConverter: MetaConverter<I> = inputConverter
        override val outputConverter: MetaConverter<O> = outputConverter
        override suspend fun execute(device: D, input: I): O? = device.execute(input)
    }
    registerAction(spec)
    return spec
}

// --- Specialized Read-only Properties ---

public fun <D : Device> CompositeSpecBuilder<D>.doubleProperty(name: Name, descriptorBuilder: PropertyDescriptorBuilder.() -> Unit = {}, read: suspend D.() -> Double?): DevicePropertySpec<D, Double> = property(name, MetaConverter.double, PropertyKind.PHYSICAL, valueDescriptor(ValueType.NUMBER, descriptorBuilder), read)
public fun <D : Device> CompositeSpecBuilder<D>.stringProperty(name: Name, descriptorBuilder: PropertyDescriptorBuilder.() -> Unit = {}, read: suspend D.() -> String?): DevicePropertySpec<D, String> = property(name, MetaConverter.string, PropertyKind.PHYSICAL, valueDescriptor(ValueType.STRING, descriptorBuilder), read)
public fun <D : Device> CompositeSpecBuilder<D>.booleanProperty(name: Name, descriptorBuilder: PropertyDescriptorBuilder.() -> Unit = {}, read: suspend D.() -> Boolean?): DevicePropertySpec<D, Boolean> = property(name, MetaConverter.boolean, PropertyKind.PHYSICAL, valueDescriptor(ValueType.BOOLEAN, descriptorBuilder), read)
public fun <D : Device> CompositeSpecBuilder<D>.metaProperty(name: Name, descriptorBuilder: PropertyDescriptorBuilder.() -> Unit = {}, read: suspend D.() -> Meta?): DevicePropertySpec<D, Meta> = property(name, MetaConverter.meta, PropertyKind.PHYSICAL, descriptorBuilder, read)
public inline fun <D : Device, reified E : Enum<E>> CompositeSpecBuilder<D>.enumProperty(name: Name, noinline descriptorBuilder: PropertyDescriptorBuilder.() -> Unit = {}, noinline read: suspend D.() -> E?): DevicePropertySpec<D, E> = property(name, MetaConverter.enum(), PropertyKind.PHYSICAL, { meta { valueType(ValueType.STRING) }; allowedValues = enumEntries<E>().map { it.name.asValue() }; descriptorBuilder() }, read)

// --- Specialized Mutable Properties ---

public fun <D : Device> CompositeSpecBuilder<D>.mutableDoubleProperty(name: Name, descriptorBuilder: PropertyDescriptorBuilder.() -> Unit = {}, read: suspend D.() -> Double?, write: suspend D.(Double) -> Unit): MutableDevicePropertySpec<D, Double> = mutableProperty(name, MetaConverter.double, PropertyKind.PHYSICAL, valueDescriptor(ValueType.NUMBER, descriptorBuilder), read, write)
public fun <D : Device> CompositeSpecBuilder<D>.mutableStringProperty(name: Name, descriptorBuilder: PropertyDescriptorBuilder.() -> Unit = {}, read: suspend D.() -> String?, write: suspend D.(String) -> Unit): MutableDevicePropertySpec<D, String> = mutableProperty(name, MetaConverter.string, PropertyKind.PHYSICAL, valueDescriptor(ValueType.STRING, descriptorBuilder), read, write)
public fun <D : Device> CompositeSpecBuilder<D>.mutableBooleanProperty(name: Name, descriptorBuilder: PropertyDescriptorBuilder.() -> Unit = {}, read: suspend D.() -> Boolean?, write: suspend D.(Boolean) -> Unit): MutableDevicePropertySpec<D, Boolean> = mutableProperty(name, MetaConverter.boolean, PropertyKind.PHYSICAL, valueDescriptor(ValueType.BOOLEAN, descriptorBuilder), read, write)
public fun <D : Device> CompositeSpecBuilder<D>.mutableMetaProperty(name: Name, descriptorBuilder: PropertyDescriptorBuilder.() -> Unit = {}, read: suspend D.() -> Meta?, write: suspend D.(Meta) -> Unit): MutableDevicePropertySpec<D, Meta> = mutableProperty(name, MetaConverter.meta, PropertyKind.PHYSICAL, descriptorBuilder, read, write)
public inline fun <D : Device, reified E : Enum<E>> CompositeSpecBuilder<D>.mutableEnumProperty(name: Name, noinline descriptorBuilder: PropertyDescriptorBuilder.() -> Unit = {}, noinline read: suspend D.() -> E?, noinline write: suspend D.(E) -> Unit): MutableDevicePropertySpec<D, E> = mutableProperty(name, MetaConverter.enum(), PropertyKind.PHYSICAL, { meta { valueType(ValueType.STRING) }; allowedValues = enumEntries<E>().map { it.name.asValue() }; descriptorBuilder() }, read, write)

// --- Specialized Actions ---

/**
 * Declares and registers an action that takes no input and returns no output.
 */
public fun <D : Device> CompositeSpecBuilder<D>.unitAction(name: Name, descriptorBuilder: ActionDescriptorBuilder.() -> Unit = {}, execute: suspend D.() -> Unit): DeviceActionSpec<D, Unit, Unit> = action(name, MetaConverter.unit, MetaConverter.unit, descriptorBuilder) { execute() }

/**
 * Declares and registers an action that takes a [Meta] object as input and returns a [Meta] object.
 */
public fun <D : Device> CompositeSpecBuilder<D>.metaAction(name: Name, descriptorBuilder: ActionDescriptorBuilder.() -> Unit = {}, execute: suspend D.(Meta) -> Meta?): DeviceActionSpec<D, Meta, Meta> = action(name, MetaConverter.meta, MetaConverter.meta, descriptorBuilder, execute)


/**
 * Declares and registers a plan-based action. Automatically adds [PlanExecutorFeature].
 */
public fun <D> CompositeSpecBuilder<D>.plan(
    name: Name,
    descriptorBuilder: ActionDescriptorBuilder.() -> Unit = {},
    block: PlanBuilder.() -> Unit,
): DeviceActionSpec<D, Unit, Unit> where D : Device, D : PlanExecutorDevice {
    feature(PlanExecutorFeature())
    val plan = plan(block)
    val planMeta = plan.toMeta()

    return unitAction(
        name = name,
        descriptorBuilder = {
            meta { "plan" put planMeta }
            descriptorBuilder()
        }
    ) {
        error(
            "Action '$name' on device '${this.name}' is backed by a TransactionPlan. " +
                    "It must be executed by a plan-aware runtime via the 'PlanExecutorDevice.executePlan' contract, not directly."
        )
    }
}

/**
 * Declares and registers a task-based action. Automatically adds [TaskExecutorFeature].
 */
@OptIn(DFExperimental::class)
public inline fun <reified I, reified O, D> CompositeSpecBuilder<D>.taskAction(
    name: Name,
    taskBlueprintId: String,
    crossinline descriptorBuilder: ActionDescriptorBuilder.() -> Unit = {},
): DeviceActionSpec<D, I, O> where D : Device, D : TaskExecutorDevice {
    feature(TaskExecutorFeature(listOf(taskBlueprintId)))

    return action(
        name = name,
        inputConverter = MetaConverter.serializable(),
        outputConverter = MetaConverter.serializable(),
        descriptorBuilder = {
            this.taskBlueprintId = taskBlueprintId
            this.taskInputTypeName = serializer<I>().descriptor.serialName
            this.taskOutputTypeName = serializer<O>().descriptor.serialName
            descriptorBuilder()
        }
    ) {
        error(
            "Action '$name' on device '${this.name}' is backed by a DataForge Task. " +
                    "It must be executed by a task-aware runtime via the 'TaskExecutorDevice.executeTask' contract, not directly."
        )
    }
}

// --- Stream DSL ---

/**
 * Declares and registers a device data stream directly within a `CompositeSpecBuilder`.
 *
 * @param T The type of the primary data object in the stream.
 * @param name The name of the stream.
 * @param descriptorBuilder A DSL block to configure the stream's descriptor.
 * @param get A suspendable factory lambda that creates a [space.kscience.controls.core.contracts.StreamPort] instance. The runtime is responsible
 *            for managing the lifecycle of the created port.
 * @return The created and registered [DeviceStreamSpec].
 */
public inline fun <reified T, D : Device> CompositeSpecBuilder<D>.stream(
    name: Name,
    noinline descriptorBuilder: StreamDescriptorBuilder.() -> Unit = {},
    noinline get: suspend D.() -> StreamPort,
): DeviceStreamSpec<D> {
    val dslBuilder = StreamDescriptorBuilder(name).apply(descriptorBuilder)
    val fqName = serializer<T>().descriptor.serialName
    val descriptor = StreamDescriptor(
        name = name,
        dataTypeFqName = fqName,
        attributes = TODO()
    )

    val spec = object : DeviceStreamSpec<D> {
        override val name: Name = name
        override val descriptor: StreamDescriptor = descriptor
        override val get: suspend D.() -> StreamPort = get
    }
    registerStream(spec)
    return spec
}