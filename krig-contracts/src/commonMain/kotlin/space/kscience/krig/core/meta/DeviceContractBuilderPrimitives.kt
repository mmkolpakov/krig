package space.kscience.krig.core.meta

import kotlin.properties.PropertyDelegateProvider
import kotlin.properties.ReadOnlyProperty
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.MetaConverter
import space.kscience.krig.api.descriptors.PropertyKind
import space.kscience.krig.api.descriptors.TypeIds

public fun DeviceContractBuilder.doubleProperty(
    kind: PropertyKind = PropertyKind.PHYSICAL,
): PropertyDelegateProvider<DeviceContractBuilder, ReadOnlyProperty<DeviceContractBuilder, DevicePropertyContract<Double>>> =
    property(MetaConverter.double, TypeIds.DOUBLE, kind)

public fun DeviceContractBuilder.intProperty(
    kind: PropertyKind = PropertyKind.PHYSICAL,
): PropertyDelegateProvider<DeviceContractBuilder, ReadOnlyProperty<DeviceContractBuilder, DevicePropertyContract<Int>>> =
    property(MetaConverter.int, TypeIds.INT, kind)

public fun DeviceContractBuilder.longProperty(
    kind: PropertyKind = PropertyKind.PHYSICAL,
): PropertyDelegateProvider<DeviceContractBuilder, ReadOnlyProperty<DeviceContractBuilder, DevicePropertyContract<Long>>> =
    property(MetaConverter.long, TypeIds.LONG, kind)

public fun DeviceContractBuilder.booleanProperty(
    kind: PropertyKind = PropertyKind.PHYSICAL,
): PropertyDelegateProvider<DeviceContractBuilder, ReadOnlyProperty<DeviceContractBuilder, DevicePropertyContract<Boolean>>> =
    property(MetaConverter.boolean, TypeIds.BOOLEAN, kind)

public fun DeviceContractBuilder.stringProperty(
    kind: PropertyKind = PropertyKind.PHYSICAL,
): PropertyDelegateProvider<DeviceContractBuilder, ReadOnlyProperty<DeviceContractBuilder, DevicePropertyContract<String>>> =
    property(MetaConverter.string, TypeIds.STRING, kind)

public fun DeviceContractBuilder.metaProperty(
    kind: PropertyKind = PropertyKind.PHYSICAL,
): PropertyDelegateProvider<DeviceContractBuilder, ReadOnlyProperty<DeviceContractBuilder, DevicePropertyContract<Meta>>> =
    property(MetaConverter.meta, TypeIds.META, kind)

public fun DeviceContractBuilder.mutableDoubleProperty(
    kind: PropertyKind = PropertyKind.PHYSICAL,
): PropertyDelegateProvider<DeviceContractBuilder, ReadOnlyProperty<DeviceContractBuilder, MutableDevicePropertyContract<Double>>> =
    mutableProperty(MetaConverter.double, TypeIds.DOUBLE, kind)

public fun DeviceContractBuilder.mutableIntProperty(
    kind: PropertyKind = PropertyKind.PHYSICAL,
): PropertyDelegateProvider<DeviceContractBuilder, ReadOnlyProperty<DeviceContractBuilder, MutableDevicePropertyContract<Int>>> =
    mutableProperty(MetaConverter.int, TypeIds.INT, kind)

public fun DeviceContractBuilder.mutableLongProperty(
    kind: PropertyKind = PropertyKind.PHYSICAL,
): PropertyDelegateProvider<DeviceContractBuilder, ReadOnlyProperty<DeviceContractBuilder, MutableDevicePropertyContract<Long>>> =
    mutableProperty(MetaConverter.long, TypeIds.LONG, kind)

public fun DeviceContractBuilder.mutableBooleanProperty(
    kind: PropertyKind = PropertyKind.PHYSICAL,
): PropertyDelegateProvider<DeviceContractBuilder, ReadOnlyProperty<DeviceContractBuilder, MutableDevicePropertyContract<Boolean>>> =
    mutableProperty(MetaConverter.boolean, TypeIds.BOOLEAN, kind)

public fun DeviceContractBuilder.mutableStringProperty(
    kind: PropertyKind = PropertyKind.PHYSICAL,
): PropertyDelegateProvider<DeviceContractBuilder, ReadOnlyProperty<DeviceContractBuilder, MutableDevicePropertyContract<String>>> =
    mutableProperty(MetaConverter.string, TypeIds.STRING, kind)

public fun DeviceContractBuilder.mutableMetaProperty(
    kind: PropertyKind = PropertyKind.PHYSICAL,
): PropertyDelegateProvider<DeviceContractBuilder, ReadOnlyProperty<DeviceContractBuilder, MutableDevicePropertyContract<Meta>>> =
    mutableProperty(MetaConverter.meta, TypeIds.META, kind)
