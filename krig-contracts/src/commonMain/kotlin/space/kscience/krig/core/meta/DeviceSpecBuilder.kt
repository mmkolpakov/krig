package space.kscience.krig.core.meta

import space.kscience.krig.api.descriptors.PropertyKind
import space.kscience.krig.api.descriptors.TypeIds
import space.kscience.krig.api.descriptors.typeIdOf
import space.kscience.krig.api.meta.serializableMetaConverter
import space.kscience.krig.core.UnstableKrigForSubclassing
import space.kscience.krig.core.contracts.Device
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.MetaConverter
import space.kscience.dataforge.names.asName
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import kotlin.properties.PropertyDelegateProvider
import kotlin.properties.ReadOnlyProperty

/**
 * Abstract builder for declaratively defining device property and action specifications
 * using Kotlin property delegation.
 *
 * Subclass this (typically as a `companion object`) to define the static schema of a device type:
 * ```kotlin
 * companion object : DeviceSpecBuilder<MyDevice>() {
 *     val temperature by doubleProperty { readTemperature() }
 *     val status by property(MetaConverter.string, TypeIds.STRING) { readStatus() }
 * }
 * ```
 */
@SubclassOptInRequired(UnstableKrigForSubclassing::class)
public abstract class DeviceSpecBuilder<D : Device> {

    @PublishedApi
    internal val _propertySpecs: MutableList<DevicePropertySpec<D, *>> = mutableListOf()

    @PublishedApi
    internal val _actionSpecs: MutableList<DeviceActionSpec<D, *, *>> = mutableListOf()

    /** All registered property specifications. */
    public val propertySpecs: List<DevicePropertySpec<D, *>> get() = _propertySpecs

    /** All registered action specifications. */
    public val actionSpecs: List<DeviceActionSpec<D, *, *>> get() = _actionSpecs

    /** Called when a device of type [D] is opened. Override to perform initialization. */
    public open suspend fun onOpen(device: D) {}

    /** Called when a device of type [D] is closed. Override to perform cleanup. */
    public open suspend fun onClose(device: D) {}

    @PublishedApi
    internal fun <S : DevicePropertySpec<D, *>> registerPropertySpec(spec: S): S {
        check(_propertySpecs.none { it.name == spec.name }) {
            "Duplicate property spec '${spec.name}' in DeviceSpecBuilder"
        }
        _propertySpecs.add(spec)
        return spec
    }

    @PublishedApi
    internal fun <S : DeviceActionSpec<D, *, *>> registerActionSpec(spec: S): S {
        check(_actionSpecs.none { it.name == spec.name }) {
            "Duplicate action spec '${spec.name}' in DeviceSpecBuilder"
        }
        _actionSpecs.add(spec)
        return spec
    }

    /**
     * Validates that [device] implements all properties and actions declared in this spec.
     * Throws [IllegalStateException] if any declared member is missing from the device.
     */
    public fun validate(device: Device) {
        val devicePropertyNames = device.propertyDescriptors.keys
        val deviceActionNames = device.actionDescriptors.keys
        for (spec in propertySpecs) {
            check(spec.name in devicePropertyNames) {
                "Device does not implement property '${spec.name}' declared in spec"
            }
        }
        for (spec in actionSpecs) {
            check(spec.name in deviceActionNames) {
                "Device does not implement action '${spec.name}' declared in spec"
            }
        }
    }

    /** Defines a read-only property with an explicit stable type id. */
    public fun <T> property(
        converter: MetaConverter<T>,
        typeId: String,
        kind: PropertyKind = PropertyKind.PHYSICAL,
        read: suspend D.() -> T?,
    ): PropertyDelegateProvider<DeviceSpecBuilder<D>, ReadOnlyProperty<DeviceSpecBuilder<D>, DevicePropertySpec<D, T>>> =
        PropertyDelegateProvider { _, property ->
            val propName = property.name.asName()
            val spec = registerPropertySpec(buildDevicePropertySpec(
                name = propName,
                converter = converter,
                kind = kind,
                valueTypeId = typeId,
                read = read,
            ))
            ReadOnlyProperty { _, _ -> spec }
        }

    /** Defines a mutable property with an explicit stable type id. */
    public fun <T> mutableProperty(
        converter: MetaConverter<T>,
        typeId: String,
        kind: PropertyKind = PropertyKind.PHYSICAL,
        read: suspend D.() -> T?,
        write: suspend D.(T) -> Unit,
    ): PropertyDelegateProvider<DeviceSpecBuilder<D>, ReadOnlyProperty<DeviceSpecBuilder<D>, MutableDevicePropertySpec<D, T>>> =
        PropertyDelegateProvider { _, property ->
            val propName = property.name.asName()
            val spec = registerPropertySpec(buildMutableDevicePropertySpec(
                name = propName,
                converter = converter,
                kind = kind,
                valueTypeId = typeId,
                read = read,
                write = write,
            ))
            ReadOnlyProperty { _, _ -> spec }
        }

    /** Defines a property backed by an explicit serializer. */
    public fun <T> serializableProperty(
        serializer: KSerializer<T>,
        kind: PropertyKind = PropertyKind.PHYSICAL,
        read: suspend D.() -> T?,
    ): PropertyDelegateProvider<DeviceSpecBuilder<D>, ReadOnlyProperty<DeviceSpecBuilder<D>, DevicePropertySpec<D, T>>> =
        property(
            converter = serializableMetaConverter(serializer),
            typeId = typeIdOf(serializer),
            kind = kind,
            read = read,
        )

    /** Defines a mutable property backed by an explicit serializer. */
    public fun <T> serializableMutableProperty(
        serializer: KSerializer<T>,
        kind: PropertyKind = PropertyKind.PHYSICAL,
        read: suspend D.() -> T?,
        write: suspend D.(T) -> Unit,
    ): PropertyDelegateProvider<DeviceSpecBuilder<D>, ReadOnlyProperty<DeviceSpecBuilder<D>, MutableDevicePropertySpec<D, T>>> =
        mutableProperty(
            converter = serializableMetaConverter(serializer),
            typeId = typeIdOf(serializer),
            kind = kind,
            read = read,
            write = write,
        )

    /** Defines a property backed by a generated serializer. */
    public inline fun <reified T> serializableProperty(
        kind: PropertyKind = PropertyKind.PHYSICAL,
        noinline read: suspend D.() -> T?,
    ): PropertyDelegateProvider<DeviceSpecBuilder<D>, ReadOnlyProperty<DeviceSpecBuilder<D>, DevicePropertySpec<D, T>>> =
        serializableProperty(serializer<T>(), kind, read)

    /** Defines a mutable property backed by a generated serializer. */
    public inline fun <reified T> serializableMutableProperty(
        kind: PropertyKind = PropertyKind.PHYSICAL,
        noinline read: suspend D.() -> T?,
        noinline write: suspend D.(T) -> Unit,
    ): PropertyDelegateProvider<DeviceSpecBuilder<D>, ReadOnlyProperty<DeviceSpecBuilder<D>, MutableDevicePropertySpec<D, T>>> =
        serializableMutableProperty(serializer<T>(), kind, read, write)

    public fun doubleProperty(
        kind: PropertyKind = PropertyKind.PHYSICAL,
        read: suspend D.() -> Double?,
    ): PropertyDelegateProvider<DeviceSpecBuilder<D>, ReadOnlyProperty<DeviceSpecBuilder<D>, DevicePropertySpec<D, Double>>> =
        property(MetaConverter.double, TypeIds.DOUBLE, kind, read)

    public fun intProperty(
        kind: PropertyKind = PropertyKind.PHYSICAL,
        read: suspend D.() -> Int?,
    ): PropertyDelegateProvider<DeviceSpecBuilder<D>, ReadOnlyProperty<DeviceSpecBuilder<D>, DevicePropertySpec<D, Int>>> =
        property(MetaConverter.int, TypeIds.INT, kind, read)

    public fun longProperty(
        kind: PropertyKind = PropertyKind.PHYSICAL,
        read: suspend D.() -> Long?,
    ): PropertyDelegateProvider<DeviceSpecBuilder<D>, ReadOnlyProperty<DeviceSpecBuilder<D>, DevicePropertySpec<D, Long>>> =
        property(MetaConverter.long, TypeIds.LONG, kind, read)

    public fun booleanProperty(
        kind: PropertyKind = PropertyKind.PHYSICAL,
        read: suspend D.() -> Boolean?,
    ): PropertyDelegateProvider<DeviceSpecBuilder<D>, ReadOnlyProperty<DeviceSpecBuilder<D>, DevicePropertySpec<D, Boolean>>> =
        property(MetaConverter.boolean, TypeIds.BOOLEAN, kind, read)

    public fun stringProperty(
        kind: PropertyKind = PropertyKind.PHYSICAL,
        read: suspend D.() -> String?,
    ): PropertyDelegateProvider<DeviceSpecBuilder<D>, ReadOnlyProperty<DeviceSpecBuilder<D>, DevicePropertySpec<D, String>>> =
        property(MetaConverter.string, TypeIds.STRING, kind, read)

    public fun metaProperty(
        kind: PropertyKind = PropertyKind.PHYSICAL,
        read: suspend D.() -> Meta?,
    ): PropertyDelegateProvider<DeviceSpecBuilder<D>, ReadOnlyProperty<DeviceSpecBuilder<D>, DevicePropertySpec<D, Meta>>> =
        property(MetaConverter.meta, TypeIds.META, kind, read)

    public fun mutableDoubleProperty(
        kind: PropertyKind = PropertyKind.PHYSICAL,
        read: suspend D.() -> Double?,
        write: suspend D.(Double) -> Unit,
    ): PropertyDelegateProvider<DeviceSpecBuilder<D>, ReadOnlyProperty<DeviceSpecBuilder<D>, MutableDevicePropertySpec<D, Double>>> =
        mutableProperty(MetaConverter.double, TypeIds.DOUBLE, kind, read, write)

    public fun mutableIntProperty(
        kind: PropertyKind = PropertyKind.PHYSICAL,
        read: suspend D.() -> Int?,
        write: suspend D.(Int) -> Unit,
    ): PropertyDelegateProvider<DeviceSpecBuilder<D>, ReadOnlyProperty<DeviceSpecBuilder<D>, MutableDevicePropertySpec<D, Int>>> =
        mutableProperty(MetaConverter.int, TypeIds.INT, kind, read, write)

    public fun mutableLongProperty(
        kind: PropertyKind = PropertyKind.PHYSICAL,
        read: suspend D.() -> Long?,
        write: suspend D.(Long) -> Unit,
    ): PropertyDelegateProvider<DeviceSpecBuilder<D>, ReadOnlyProperty<DeviceSpecBuilder<D>, MutableDevicePropertySpec<D, Long>>> =
        mutableProperty(MetaConverter.long, TypeIds.LONG, kind, read, write)

    public fun mutableBooleanProperty(
        kind: PropertyKind = PropertyKind.PHYSICAL,
        read: suspend D.() -> Boolean?,
        write: suspend D.(Boolean) -> Unit,
    ): PropertyDelegateProvider<DeviceSpecBuilder<D>, ReadOnlyProperty<DeviceSpecBuilder<D>, MutableDevicePropertySpec<D, Boolean>>> =
        mutableProperty(MetaConverter.boolean, TypeIds.BOOLEAN, kind, read, write)

    public fun mutableStringProperty(
        kind: PropertyKind = PropertyKind.PHYSICAL,
        read: suspend D.() -> String?,
        write: suspend D.(String) -> Unit,
    ): PropertyDelegateProvider<DeviceSpecBuilder<D>, ReadOnlyProperty<DeviceSpecBuilder<D>, MutableDevicePropertySpec<D, String>>> =
        mutableProperty(MetaConverter.string, TypeIds.STRING, kind, read, write)

    public fun mutableMetaProperty(
        kind: PropertyKind = PropertyKind.PHYSICAL,
        read: suspend D.() -> Meta?,
        write: suspend D.(Meta) -> Unit,
    ): PropertyDelegateProvider<DeviceSpecBuilder<D>, ReadOnlyProperty<DeviceSpecBuilder<D>, MutableDevicePropertySpec<D, Meta>>> =
        mutableProperty(MetaConverter.meta, TypeIds.META, kind, read, write)

    /**
     * Defines an action specification.
     */
    public inline fun <reified I, reified O> action(
        inputConverter: MetaConverter<I>,
        outputConverter: MetaConverter<O>,
        noinline execute: suspend D.(I) -> O?,
    ): PropertyDelegateProvider<DeviceSpecBuilder<D>, ReadOnlyProperty<DeviceSpecBuilder<D>, DeviceActionSpec<D, I, O>>> =
        PropertyDelegateProvider { _, property ->
            val actionName = property.name.asName()
            val spec = registerActionSpec(buildDeviceActionSpec(
                name = actionName,
                inputConverter = inputConverter,
                outputConverter = outputConverter,
                execute = execute,
            ))
            ReadOnlyProperty { _, _ -> spec }
        }
}
