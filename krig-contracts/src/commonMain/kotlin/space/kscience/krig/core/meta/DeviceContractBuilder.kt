package space.kscience.krig.core.meta

import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import space.kscience.krig.api.descriptors.ActionDescriptor
import space.kscience.krig.api.descriptors.PropertyDescriptor
import space.kscience.krig.api.descriptors.PropertyKind
import space.kscience.krig.api.descriptors.TypeId
import space.kscience.krig.api.descriptors.toMetaDescriptor
import space.kscience.dataforge.meta.descriptors.MetaDescriptor
import space.kscience.krig.api.descriptors.attributes.AccessAttribute
import space.kscience.krig.api.descriptors.attributes.OperationAttributeKeys
import space.kscience.krig.api.descriptors.operationAttributes
import space.kscience.krig.api.descriptors.typeIdOf
import space.kscience.krig.api.meta.serializableMetaConverter
import space.kscience.dataforge.meta.MetaConverter
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName
import kotlin.properties.PropertyDelegateProvider
import kotlin.properties.ReadOnlyProperty

/**
 * Pure contract builder for device manifests.
 *
 * This builder records only contracts: descriptors and typed converters.
 * Hardware/simulation logic is supplied by a backend such as
 * [deviceBackend][space.kscience.krig.core.contracts.deviceBackend].
 */
public abstract class DeviceContractBuilder {
    @PublishedApi
    internal val registeredPropertyContracts: MutableList<DevicePropertyContract<*>> = mutableListOf()

    @PublishedApi
    internal val registeredActionContracts: MutableList<DeviceActionContract<*, *>> = mutableListOf()

    /** All registered property contracts. */
    public val propertyContracts: List<DevicePropertyContract<*>> get() = registeredPropertyContracts

    /** All registered action contracts. */
    public val actionContracts: List<DeviceActionContract<*, *>> get() = registeredActionContracts

    @PublishedApi
    internal fun <S : DevicePropertyContract<*>> registerPropertyContract(contract: S): S {
        check(registeredPropertyContracts.none { it.name == contract.name }) {
            "Duplicate property contract '${contract.name}' in DeviceContractBuilder"
        }
        registeredPropertyContracts.add(contract)
        return contract
    }

    @PublishedApi
    internal fun <S : DeviceActionContract<*, *>> registerActionContract(contract: S): S {
        check(registeredActionContracts.none { it.name == contract.name }) {
            "Duplicate action contract '${contract.name}' in DeviceContractBuilder"
        }
        registeredActionContracts.add(contract)
        return contract
    }

    /** Defines a read-only property contract with an explicit stable type id. */
    public fun <T> property(
        converter: MetaConverter<T>,
        typeId: TypeId,
        kind: PropertyKind = PropertyKind.PHYSICAL,
    ): PropertyDelegateProvider<DeviceContractBuilder, ReadOnlyProperty<DeviceContractBuilder, DevicePropertyContract<T>>> =
        PropertyDelegateProvider { _, property ->
            val contract = registerPropertyContract(
                devicePropertyContract(
                    name = property.name.asName(),
                    converter = converter,
                    kind = kind,
                    valueTypeId = typeId,
                ),
            )
            ReadOnlyProperty { _, _ -> contract }
        }

    /** Defines a mutable property contract with an explicit stable type id. */
    public fun <T> mutableProperty(
        converter: MetaConverter<T>,
        typeId: TypeId,
        kind: PropertyKind = PropertyKind.PHYSICAL,
    ): PropertyDelegateProvider<DeviceContractBuilder, ReadOnlyProperty<DeviceContractBuilder, MutableDevicePropertyContract<T>>> =
        PropertyDelegateProvider { _, property ->
            val contract = registerPropertyContract(
                mutableDevicePropertyContract(
                    name = property.name.asName(),
                    converter = converter,
                    kind = kind,
                    valueTypeId = typeId,
                ),
            )
            ReadOnlyProperty { _, _ -> contract }
        }

    public fun <T> serializableProperty(
        serializer: KSerializer<T>,
        kind: PropertyKind = PropertyKind.PHYSICAL,
    ): PropertyDelegateProvider<DeviceContractBuilder, ReadOnlyProperty<DeviceContractBuilder, DevicePropertyContract<T>>> =
        PropertyDelegateProvider { _, property ->
            val contract = registerPropertyContract(
                devicePropertyContract(
                    name = property.name.asName(),
                    converter = serializableMetaConverter(serializer),
                    kind = kind,
                    valueTypeId = typeIdOf(serializer),
                    metaDescriptor = serializer.descriptor.toMetaDescriptor(),
                ),
            )
            ReadOnlyProperty { _, _ -> contract }
        }

    public fun <T> serializableMutableProperty(
        serializer: KSerializer<T>,
        kind: PropertyKind = PropertyKind.PHYSICAL,
    ): PropertyDelegateProvider<DeviceContractBuilder, ReadOnlyProperty<DeviceContractBuilder, MutableDevicePropertyContract<T>>> =
        PropertyDelegateProvider { _, property ->
            val contract = registerPropertyContract(
                mutableDevicePropertyContract(
                    name = property.name.asName(),
                    converter = serializableMetaConverter(serializer),
                    kind = kind,
                    valueTypeId = typeIdOf(serializer),
                    metaDescriptor = serializer.descriptor.toMetaDescriptor(),
                ),
            )
            ReadOnlyProperty { _, _ -> contract }
        }

    public inline fun <reified T> serializableProperty(
        kind: PropertyKind = PropertyKind.PHYSICAL,
    ): PropertyDelegateProvider<DeviceContractBuilder, ReadOnlyProperty<DeviceContractBuilder, DevicePropertyContract<T>>> =
        serializableProperty(serializer<T>(), kind)

    public inline fun <reified T> serializableMutableProperty(
        kind: PropertyKind = PropertyKind.PHYSICAL,
    ): PropertyDelegateProvider<DeviceContractBuilder, ReadOnlyProperty<DeviceContractBuilder, MutableDevicePropertyContract<T>>> =
        serializableMutableProperty(serializer<T>(), kind)

    public fun <I, O> action(
        inputConverter: MetaConverter<I>,
        outputConverter: MetaConverter<O>,
    ): PropertyDelegateProvider<DeviceContractBuilder, ReadOnlyProperty<DeviceContractBuilder, DeviceActionContract<I, O>>> =
        PropertyDelegateProvider { _, property ->
            val contract = registerActionContract(
                deviceActionContract(
                    name = property.name.asName(),
                    inputConverter = inputConverter,
                    outputConverter = outputConverter,
                ),
            )
            ReadOnlyProperty { _, _ -> contract }
        }
}

public fun <T> devicePropertyContract(
    name: Name,
    converter: MetaConverter<T>,
    kind: PropertyKind,
    valueTypeId: TypeId,
    metaDescriptor: MetaDescriptor = MetaDescriptor(),
): DevicePropertyContract<T> = SimpleDevicePropertyContract(
    name = name,
    descriptor = propertyDescriptor(name, kind, valueTypeId, metaDescriptor, mutable = false),
    converter = converter,
)

public fun <T> mutableDevicePropertyContract(
    name: Name,
    converter: MetaConverter<T>,
    kind: PropertyKind,
    valueTypeId: TypeId,
    metaDescriptor: MetaDescriptor = MetaDescriptor(),
): MutableDevicePropertyContract<T> = SimpleMutableDevicePropertyContract(
    name = name,
    descriptor = propertyDescriptor(name, kind, valueTypeId, metaDescriptor, mutable = true),
    converter = converter,
)

public fun <I, O> deviceActionContract(
    name: Name,
    inputConverter: MetaConverter<I>,
    outputConverter: MetaConverter<O>,
    inputMetaDescriptor: MetaDescriptor = MetaDescriptor(),
    outputMetaDescriptor: MetaDescriptor = MetaDescriptor(),
): DeviceActionContract<I, O> = SimpleDeviceActionContract(
    name = name,
    descriptor = ActionDescriptor(
        name = name,
        inputMetaDescriptor = inputMetaDescriptor,
        outputMetaDescriptor = outputMetaDescriptor,
    ),
    inputConverter = inputConverter,
    outputConverter = outputConverter,
)

/**
 * [deviceActionContract] that derives the action's input/output [MetaDescriptor]s from the serializers
 * of [I] and [O]. Use when both boundary types are `@Serializable`, so the contract self-describes its
 * argument structure (and a JSON Schema for external clients) without hand-written descriptors.
 */
public inline fun <reified I, reified O> serializableActionContract(
    name: Name,
    inputConverter: MetaConverter<I>,
    outputConverter: MetaConverter<O>,
): DeviceActionContract<I, O> = deviceActionContract(
    name = name,
    inputConverter = inputConverter,
    outputConverter = outputConverter,
    inputMetaDescriptor = serializer<I>().descriptor.toMetaDescriptor(),
    outputMetaDescriptor = serializer<O>().descriptor.toMetaDescriptor(),
)

private fun propertyDescriptor(
    name: Name,
    kind: PropertyKind,
    valueTypeId: TypeId,
    metaDescriptor: MetaDescriptor,
    mutable: Boolean,
): PropertyDescriptor = PropertyDescriptor(
    name = name,
    kind = kind,
    valueTypeId = valueTypeId,
    metaDescriptor = metaDescriptor,
    attributes = operationAttributes {
        OperationAttributeKeys.Access(AccessAttribute(readable = true, mutable = mutable))
    },
)

private data class SimpleDevicePropertyContract<T>(
    override val name: Name,
    override val descriptor: PropertyDescriptor,
    override val converter: MetaConverter<T>,
) : DevicePropertyContract<T>

private data class SimpleMutableDevicePropertyContract<T>(
    override val name: Name,
    override val descriptor: PropertyDescriptor,
    override val converter: MetaConverter<T>,
) : MutableDevicePropertyContract<T>

private data class SimpleDeviceActionContract<I, O>(
    override val name: Name,
    override val descriptor: ActionDescriptor,
    override val inputConverter: MetaConverter<I>,
    override val outputConverter: MetaConverter<O>,
) : DeviceActionContract<I, O>
