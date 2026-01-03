package space.kscience.controls.composite.dsl.properties

import kotlinx.serialization.serializer
import space.kscience.controls.composite.dsl.DeviceSpecification
import space.kscience.controls.core.contracts.Device
import space.kscience.controls.core.meta.DevicePropertySpec
import space.kscience.controls.core.meta.MutableDevicePropertySpec
import space.kscience.controls.api.descriptors.PropertyDescriptor
import space.kscience.controls.api.descriptors.PropertyKind
import space.kscience.dataforge.meta.*
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.parseAsName
import kotlin.enums.enumEntries
import kotlin.properties.PropertyDelegateProvider
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KType
import kotlin.reflect.typeOf

@PublishedApi
internal fun valueDescriptor(
    valueType: ValueType,
    userBuilder: PropertyDescriptorBuilder.() -> Unit
): PropertyDescriptorBuilder.() -> Unit = {
    meta { this.valueType(valueType) }
    userBuilder()
}

/**
 * A centralized, internal factory for creating property delegate providers.
 *
 * This function handles all the boilerplate logic for:
 * 1. Resolving the property name.
 * 2. Creating and configuring the [PropertyDescriptorBuilder].
 * 3. Building the final [PropertyDescriptor], including serializable validation rules.
 * 4. Creating the appropriate [DevicePropertySpec] or [MutableDevicePropertySpec] instance.
 * 5. Wrapping the `write` logic with runtime validation predicates.
 * 6. Registering the specification with the correct visibility.
 */
@PublishedApi
internal inline fun <D : Device, reified T> createPropertyDelegateProvider(
    crossinline specProvider: DeviceSpecification<D>.() -> Unit,
    name: Name?,
    kind: PropertyKind,
    mutable: Boolean,
    converter: MetaConverter<T>,
    crossinline descriptorBuilder: PropertyDescriptorBuilder.() -> Unit,
    noinline read: (suspend D.() -> T?)?,
    noinline write: (suspend D.(value: T) -> Unit)?,
    noinline validation: (ValidationBuilder<D, T>.() -> Unit)? = null
): PropertyDelegateProvider<DeviceSpecification<D>, ReadOnlyProperty<DeviceSpecification<D>, DevicePropertySpec<D, T>>> =
    PropertyDelegateProvider { thisRef, property ->
        thisRef.apply(specProvider)

        val propertyName = name ?: property.name.parseAsName()

        // 1. Resolve validation rules
        val validationBuilder = validation?.let { ValidationBuilder<D, T>().apply(it) }
        val runtimeValidationLogic = validationBuilder?.build()

        val dslBuilder = PropertyDescriptorBuilder(propertyName).apply {
            this.kind = kind
            // Add serializable validation rules to the descriptor builder
            validationBuilder?.descriptors?.let { this.validationRules.addAll(it) }
            descriptorBuilder()
        }
        val descriptor = dslBuilder.build(
            mutable = mutable,
            valueTypeName = serializer<T>().descriptor.serialName
        )

        // 2. Wrap the original write logic with runtime validation
        val finalWrite: (suspend D.(T) -> Unit)? = write?.let { originalWrite ->
            if (runtimeValidationLogic != null) {
                { value: T ->
                    // Execute runtime predicates first (throws DeviceFaultException on failure)
                    runtimeValidationLogic(this, value)
                    // If validation passes, execute original write logic
                    originalWrite(this, value)
                }
            } else {
                originalWrite // No runtime validation needed
            }
        }


        // 3. Create and register the specification
        val spec: DevicePropertySpec<D, T> = if (mutable && finalWrite != null) {
            object : MutableDevicePropertySpec<D, T> {
                override val name: Name = propertyName
                override val descriptor: PropertyDescriptor = descriptor
                override val converter: MetaConverter<T> = converter
                override val valueType: KType = typeOf<T>()
                override suspend fun read(device: D): T? = read?.invoke(device)
                override suspend fun write(device: D, value: T): Unit = finalWrite.invoke(device, value)
            }
        } else {
            object : DevicePropertySpec<D, T> {
                override val name: Name = propertyName
                override val descriptor: PropertyDescriptor = descriptor
                override val converter: MetaConverter<T> = converter
                override val valueType: KType = typeOf<T>()
                override suspend fun read(device: D): T? = read?.invoke(device)
            }
        }

        thisRef.registerPropertySpec(spec)

        ReadOnlyProperty { _, _ -> spec }
    }


/**
 * Creates a delegate for a read-only property specification.
 *
 * @see property
 */
public inline fun <D : Device, reified T> DeviceSpecification<D>.property(
    converter: MetaConverter<T>,
    noinline descriptorBuilder: PropertyDescriptorBuilder.() -> Unit = {},
    name: Name? = null,
    noinline read: suspend D.() -> T?,
): PropertyDelegateProvider<DeviceSpecification<D>, ReadOnlyProperty<DeviceSpecification<D>, DevicePropertySpec<D, T>>> =
    createPropertyDelegateProvider(
        specProvider = {},
        name = name,
        kind = PropertyKind.PHYSICAL,
        mutable = false,
        converter = converter,
        descriptorBuilder = descriptorBuilder,
        read = read,
        write = null
    )


/**
 * Creates a delegate for a mutable property specification, automatically registering it with respect to visibility scope.
 * It now accepts an optional `validation` block to define declarative and runtime constraints.
 *
 * @param validation An optional DSL block for defining validation rules that are checked before writing the new value.
 * @see property
 */
@Suppress("UNCHECKED_CAST")
public inline fun <D : Device, reified T> DeviceSpecification<D>.mutableProperty(
    converter: MetaConverter<T>,
    noinline descriptorBuilder: PropertyDescriptorBuilder.() -> Unit = {},
    name: Name? = null,
    noinline read: suspend D.() -> T?,
    noinline write: suspend D.(value: T) -> Unit,
    noinline validation: (ValidationBuilder<D, T>.() -> Unit)? = null,
): PropertyDelegateProvider<DeviceSpecification<D>, ReadOnlyProperty<DeviceSpecification<D>, MutableDevicePropertySpec<D, T>>> =
    createPropertyDelegateProvider(
        specProvider = {},
        name = name,
        kind = PropertyKind.PHYSICAL,
        mutable = true,
        converter = converter,
        descriptorBuilder = descriptorBuilder,
        read = read,
        write = write,
        validation = validation
    ) as PropertyDelegateProvider<DeviceSpecification<D>, ReadOnlyProperty<DeviceSpecification<D>, MutableDevicePropertySpec<D, T>>>


// Type-safe helpers for common types, now as extensions on DeviceSpecification

/**
 * Declares a read-only property of type [Number]. The property is semantically classified as [PropertyKind.PHYSICAL].
 *
 * @see property
 */
public fun <D : Device> DeviceSpecification<D>.numberProperty(
    descriptorBuilder: PropertyDescriptorBuilder.() -> Unit = {},
    name: Name? = null,
    read: suspend D.() -> Number?,
): PropertyDelegateProvider<DeviceSpecification<D>, ReadOnlyProperty<DeviceSpecification<D>, DevicePropertySpec<D, Number>>> = property(
    MetaConverter.number,
    valueDescriptor(ValueType.NUMBER, descriptorBuilder),
    name,
    read
)

/**
 * Declares a read-only property of type [Double]. The property is semantically classified as [PropertyKind.PHYSICAL].
 *
 * @see property
 */
public fun <D : Device> DeviceSpecification<D>.doubleProperty(
    descriptorBuilder: PropertyDescriptorBuilder.() -> Unit = {},
    name: Name? = null,
    read: suspend D.() -> Double?,
): PropertyDelegateProvider<DeviceSpecification<D>, ReadOnlyProperty<DeviceSpecification<D>, DevicePropertySpec<D, Double>>> = property(
    MetaConverter.double,
    valueDescriptor(ValueType.NUMBER, descriptorBuilder),
    name,
    read
)

/**
 * Declares a read-only property of type [Float]. The property is semantically classified as [PropertyKind.PHYSICAL].
 *
 * @see property
 */
public fun <D : Device> DeviceSpecification<D>.floatProperty(
    descriptorBuilder: PropertyDescriptorBuilder.() -> Unit = {},
    name: Name? = null,
    read: suspend D.() -> Float?,
): PropertyDelegateProvider<DeviceSpecification<D>, ReadOnlyProperty<DeviceSpecification<D>, DevicePropertySpec<D, Float>>> = property(
    MetaConverter.float,
    valueDescriptor(ValueType.NUMBER, descriptorBuilder),
    name,
    read
)

/**
 * Declares a read-only property of type [Long]. The property is semantically classified as [PropertyKind.PHYSICAL].
 *
 * @see property
 */
public fun <D : Device> DeviceSpecification<D>.longProperty(
    descriptorBuilder: PropertyDescriptorBuilder.() -> Unit = {},
    name: Name? = null,
    read: suspend D.() -> Long?,
): PropertyDelegateProvider<DeviceSpecification<D>, ReadOnlyProperty<DeviceSpecification<D>, DevicePropertySpec<D, Long>>> = property(
    MetaConverter.long,
    valueDescriptor(ValueType.NUMBER, descriptorBuilder),
    name,
    read
)

/**
 * Declares a read-only property of type [Int]. The property is semantically classified as [PropertyKind.PHYSICAL].
 *
 * @see property
 */
public fun <D : Device> DeviceSpecification<D>.intProperty(
    descriptorBuilder: PropertyDescriptorBuilder.() -> Unit = {},
    name: Name? = null,
    read: suspend D.() -> Int?,
): PropertyDelegateProvider<DeviceSpecification<D>, ReadOnlyProperty<DeviceSpecification<D>, DevicePropertySpec<D, Int>>> = property(
    MetaConverter.int,
    valueDescriptor(ValueType.NUMBER, descriptorBuilder),
    name,
    read
)

/**
 * Declares a read-only property of type [String]. The property is semantically classified as [PropertyKind.PHYSICAL].
 *
 * @see property
 */
public fun <D : Device> DeviceSpecification<D>.stringProperty(
    descriptorBuilder: PropertyDescriptorBuilder.() -> Unit = {},
    name: Name? = null,
    read: suspend D.() -> String?,
): PropertyDelegateProvider<DeviceSpecification<D>, ReadOnlyProperty<DeviceSpecification<D>, DevicePropertySpec<D, String>>> = property(
    MetaConverter.string,
    valueDescriptor(ValueType.STRING, descriptorBuilder),
    name,
    read
)

/**
 * Declares a read-only property of type [Boolean]. The property is semantically classified as [PropertyKind.PHYSICAL].
 *
 * @see property
 */
public fun <D : Device> DeviceSpecification<D>.booleanProperty(
    descriptorBuilder: PropertyDescriptorBuilder.() -> Unit = {},
    name: Name? = null,
    read: suspend D.() -> Boolean?,
): PropertyDelegateProvider<DeviceSpecification<D>, ReadOnlyProperty<DeviceSpecification<D>, DevicePropertySpec<D, Boolean>>> = property(
    MetaConverter.boolean,
    valueDescriptor(ValueType.BOOLEAN, descriptorBuilder),
    name,
    read
)

/**
 * Declares a read-only property of type [Meta]. The property is semantically classified as [PropertyKind.PHYSICAL].
 *
 * @see property
 */
public fun <D : Device> DeviceSpecification<D>.metaProperty(
    descriptorBuilder: PropertyDescriptorBuilder.() -> Unit = {},
    name: Name? = null,
    read: suspend D.() -> Meta?,
): PropertyDelegateProvider<DeviceSpecification<D>, ReadOnlyProperty<DeviceSpecification<D>, DevicePropertySpec<D, Meta>>> = property(
    MetaConverter.meta, descriptorBuilder, name, read
)

/**
 * Declares a read-only property of an enum type. The property is semantically classified as [PropertyKind.PHYSICAL].
 *
 * @see property
 */
public inline fun <D : Device, reified E : Enum<E>> DeviceSpecification<D>.enumProperty(
    noinline descriptorBuilder: PropertyDescriptorBuilder.() -> Unit = {},
    name: Name? = null,
    noinline read: suspend D.() -> E?,
): PropertyDelegateProvider<DeviceSpecification<D>, ReadOnlyProperty<DeviceSpecification<D>, DevicePropertySpec<D, E>>> = property(
    MetaConverter.enum(), {
        meta { valueType(ValueType.STRING) }
        allowedValues = enumEntries<E>().map { it.name.asValue() }
        descriptorBuilder()
    }, name, read
)

/**
 * Declares a mutable property of type [Boolean]. The property is semantically classified as [PropertyKind.PHYSICAL].
 *
 * @see mutableProperty
 */
public fun <D : Device> DeviceSpecification<D>.mutableBooleanProperty(
    descriptorBuilder: PropertyDescriptorBuilder.() -> Unit = {},
    name: Name? = null,
    read: suspend D.() -> Boolean?,
    write: suspend D.(Boolean) -> Unit,
    validation: (ValidationBuilder<D, Boolean>.() -> Unit)? = null,
): PropertyDelegateProvider<DeviceSpecification<D>, ReadOnlyProperty<DeviceSpecification<D>, MutableDevicePropertySpec<D, Boolean>>> = mutableProperty(
    MetaConverter.boolean,
    descriptorBuilder,
    name,
    read,
    write,
    validation
)

/**
 * Declares a mutable property of type [Double]. The property is semantically classified as [PropertyKind.PHYSICAL].
 *
 * @see mutableProperty
 */
public fun <D : Device> DeviceSpecification<D>.mutableDoubleProperty(
    descriptorBuilder: PropertyDescriptorBuilder.() -> Unit = {},
    name: Name? = null,
    read: suspend D.() -> Double?,
    write: suspend D.(Double) -> Unit,
    validation: (ValidationBuilder<D, Double>.() -> Unit)? = null,
): PropertyDelegateProvider<DeviceSpecification<D>, ReadOnlyProperty<DeviceSpecification<D>, MutableDevicePropertySpec<D, Double>>> = mutableProperty(
    MetaConverter.double,
    descriptorBuilder,
    name,
    read,
    write,
    validation
)

/**
 * Declares a mutable property of type [Float]. The property is semantically classified as [PropertyKind.PHYSICAL].
 *
 * @see mutableProperty
 */
public fun <D : Device> DeviceSpecification<D>.mutableFloatProperty(
    descriptorBuilder: PropertyDescriptorBuilder.() -> Unit = {},
    name: Name? = null,
    read: suspend D.() -> Float?,
    write: suspend D.(Float) -> Unit,
    validation: (ValidationBuilder<D, Float>.() -> Unit)? = null,
): PropertyDelegateProvider<DeviceSpecification<D>, ReadOnlyProperty<DeviceSpecification<D>, MutableDevicePropertySpec<D, Float>>> = mutableProperty(
    MetaConverter.float,
    descriptorBuilder,
    name,
    read,
    write,
    validation
)

/**
 * Declares a mutable property of type [Long]. The property is semantically classified as [PropertyKind.PHYSICAL].
 *
 * @see mutableProperty
 */
public fun <D : Device> DeviceSpecification<D>.mutableLongProperty(
    descriptorBuilder: PropertyDescriptorBuilder.() -> Unit = {},
    name: Name? = null,
    read: suspend D.() -> Long?,
    write: suspend D.(Long) -> Unit,
    validation: (ValidationBuilder<D, Long>.() -> Unit)? = null,
): PropertyDelegateProvider<DeviceSpecification<D>, ReadOnlyProperty<DeviceSpecification<D>, MutableDevicePropertySpec<D, Long>>> = mutableProperty(
    MetaConverter.long,
    descriptorBuilder,
    name,
    read,
    write,
    validation
)

/**
 * Declares a mutable property of type [Int]. The property is semantically classified as [PropertyKind.PHYSICAL].
 *
 * @see mutableProperty
 */
public fun <D : Device> DeviceSpecification<D>.mutableIntProperty(
    descriptorBuilder: PropertyDescriptorBuilder.() -> Unit = {},
    name: Name? = null,
    read: suspend D.() -> Int?,
    write: suspend D.(Int) -> Unit,
    validation: (ValidationBuilder<D, Int>.() -> Unit)? = null,
): PropertyDelegateProvider<DeviceSpecification<D>, ReadOnlyProperty<DeviceSpecification<D>, MutableDevicePropertySpec<D, Int>>> = mutableProperty(
    MetaConverter.int,
    descriptorBuilder,
    name,
    read,
    write,
    validation
)

/**
 * Declares a mutable property of type [String]. The property is semantically classified as [PropertyKind.PHYSICAL].
 *
 * @see mutableProperty
 */
public fun <D : Device> DeviceSpecification<D>.mutableStringProperty(
    descriptorBuilder: PropertyDescriptorBuilder.() -> Unit = {},
    name: Name? = null,
    read: suspend D.() -> String?,
    write: suspend D.(String) -> Unit,
    validation: (ValidationBuilder<D, String>.() -> Unit)? = null,
): PropertyDelegateProvider<DeviceSpecification<D>, ReadOnlyProperty<DeviceSpecification<D>, MutableDevicePropertySpec<D, String>>> = mutableProperty(
    MetaConverter.string,
    descriptorBuilder, name, read, write, validation
)

/**
 * Declares a mutable property of an enum type. The property is semantically classified as [PropertyKind.PHYSICAL].
 *
 * @see mutableProperty
 */
public inline fun <D : Device, reified E : Enum<E>> DeviceSpecification<D>.mutableEnumProperty(
    noinline descriptorBuilder: PropertyDescriptorBuilder.() -> Unit = {},
    name: Name? = null,
    noinline read: suspend D.() -> E?,
    noinline write: suspend D.(E) -> Unit,
    noinline validation: (ValidationBuilder<D, E>.() -> Unit)? = null,
): PropertyDelegateProvider<DeviceSpecification<D>, ReadOnlyProperty<DeviceSpecification<D>, MutableDevicePropertySpec<D, E>>> = mutableProperty(
    MetaConverter.enum(), {
        meta { valueType(ValueType.STRING) }
        allowedValues = enumEntries<E>().map { it.name.asValue() }
        descriptorBuilder()
    }, name, read, write, validation
)

/**
 * Declares a mutable property of type [Meta]. The property is semantically classified as [PropertyKind.PHYSICAL].
 *
 * @see mutableProperty
 */
public fun <D : Device> DeviceSpecification<D>.mutableMetaProperty(
    descriptorBuilder: PropertyDescriptorBuilder.() -> Unit = {},
    name: Name? = null,
    read: suspend D.() -> Meta?,
    write: suspend D.(Meta) -> Unit,
    validation: (ValidationBuilder<D, Meta>.() -> Unit)? = null,
): PropertyDelegateProvider<DeviceSpecification<D>, ReadOnlyProperty<DeviceSpecification<D>, MutableDevicePropertySpec<D, Meta>>> = mutableProperty(
    MetaConverter.meta, descriptorBuilder, name, read, write, validation
)