package space.kscience.krig.core.meta

import kotlin.properties.PropertyDelegateProvider
import kotlin.properties.ReadOnlyProperty
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.MetaConverter
import space.kscience.krig.api.descriptors.PropertyKind
import space.kscience.krig.api.descriptors.TypeIds
import space.kscience.krig.core.contracts.Device

public fun <D : Device> DeviceSpecBuilder<D>.doubleProperty(
    kind: PropertyKind = PropertyKind.PHYSICAL,
    read: suspend D.() -> Double?,
): PropertyDelegateProvider<DeviceSpecBuilder<D>, ReadOnlyProperty<DeviceSpecBuilder<D>, DevicePropertySpec<D, Double>>> =
    property(MetaConverter.double, TypeIds.DOUBLE, kind, read)

public fun <D : Device> DeviceSpecBuilder<D>.intProperty(
    kind: PropertyKind = PropertyKind.PHYSICAL,
    read: suspend D.() -> Int?,
): PropertyDelegateProvider<DeviceSpecBuilder<D>, ReadOnlyProperty<DeviceSpecBuilder<D>, DevicePropertySpec<D, Int>>> =
    property(MetaConverter.int, TypeIds.INT, kind, read)

public fun <D : Device> DeviceSpecBuilder<D>.longProperty(
    kind: PropertyKind = PropertyKind.PHYSICAL,
    read: suspend D.() -> Long?,
): PropertyDelegateProvider<DeviceSpecBuilder<D>, ReadOnlyProperty<DeviceSpecBuilder<D>, DevicePropertySpec<D, Long>>> =
    property(MetaConverter.long, TypeIds.LONG, kind, read)

public fun <D : Device> DeviceSpecBuilder<D>.booleanProperty(
    kind: PropertyKind = PropertyKind.PHYSICAL,
    read: suspend D.() -> Boolean?,
): PropertyDelegateProvider<DeviceSpecBuilder<D>, ReadOnlyProperty<DeviceSpecBuilder<D>, DevicePropertySpec<D, Boolean>>> =
    property(MetaConverter.boolean, TypeIds.BOOLEAN, kind, read)

public fun <D : Device> DeviceSpecBuilder<D>.stringProperty(
    kind: PropertyKind = PropertyKind.PHYSICAL,
    read: suspend D.() -> String?,
): PropertyDelegateProvider<DeviceSpecBuilder<D>, ReadOnlyProperty<DeviceSpecBuilder<D>, DevicePropertySpec<D, String>>> =
    property(MetaConverter.string, TypeIds.STRING, kind, read)

public fun <D : Device> DeviceSpecBuilder<D>.metaProperty(
    kind: PropertyKind = PropertyKind.PHYSICAL,
    read: suspend D.() -> Meta?,
): PropertyDelegateProvider<DeviceSpecBuilder<D>, ReadOnlyProperty<DeviceSpecBuilder<D>, DevicePropertySpec<D, Meta>>> =
    property(MetaConverter.meta, TypeIds.META, kind, read)

public fun <D : Device> DeviceSpecBuilder<D>.mutableDoubleProperty(
    kind: PropertyKind = PropertyKind.PHYSICAL,
    read: suspend D.() -> Double?,
    write: suspend D.(Double) -> Unit,
): PropertyDelegateProvider<DeviceSpecBuilder<D>, ReadOnlyProperty<DeviceSpecBuilder<D>, MutableDevicePropertySpec<D, Double>>> =
    mutableProperty(MetaConverter.double, TypeIds.DOUBLE, kind, read, write)

public fun <D : Device> DeviceSpecBuilder<D>.mutableIntProperty(
    kind: PropertyKind = PropertyKind.PHYSICAL,
    read: suspend D.() -> Int?,
    write: suspend D.(Int) -> Unit,
): PropertyDelegateProvider<DeviceSpecBuilder<D>, ReadOnlyProperty<DeviceSpecBuilder<D>, MutableDevicePropertySpec<D, Int>>> =
    mutableProperty(MetaConverter.int, TypeIds.INT, kind, read, write)

public fun <D : Device> DeviceSpecBuilder<D>.mutableLongProperty(
    kind: PropertyKind = PropertyKind.PHYSICAL,
    read: suspend D.() -> Long?,
    write: suspend D.(Long) -> Unit,
): PropertyDelegateProvider<DeviceSpecBuilder<D>, ReadOnlyProperty<DeviceSpecBuilder<D>, MutableDevicePropertySpec<D, Long>>> =
    mutableProperty(MetaConverter.long, TypeIds.LONG, kind, read, write)

public fun <D : Device> DeviceSpecBuilder<D>.mutableBooleanProperty(
    kind: PropertyKind = PropertyKind.PHYSICAL,
    read: suspend D.() -> Boolean?,
    write: suspend D.(Boolean) -> Unit,
): PropertyDelegateProvider<DeviceSpecBuilder<D>, ReadOnlyProperty<DeviceSpecBuilder<D>, MutableDevicePropertySpec<D, Boolean>>> =
    mutableProperty(MetaConverter.boolean, TypeIds.BOOLEAN, kind, read, write)

public fun <D : Device> DeviceSpecBuilder<D>.mutableStringProperty(
    kind: PropertyKind = PropertyKind.PHYSICAL,
    read: suspend D.() -> String?,
    write: suspend D.(String) -> Unit,
): PropertyDelegateProvider<DeviceSpecBuilder<D>, ReadOnlyProperty<DeviceSpecBuilder<D>, MutableDevicePropertySpec<D, String>>> =
    mutableProperty(MetaConverter.string, TypeIds.STRING, kind, read, write)

public fun <D : Device> DeviceSpecBuilder<D>.mutableMetaProperty(
    kind: PropertyKind = PropertyKind.PHYSICAL,
    read: suspend D.() -> Meta?,
    write: suspend D.(Meta) -> Unit,
): PropertyDelegateProvider<DeviceSpecBuilder<D>, ReadOnlyProperty<DeviceSpecBuilder<D>, MutableDevicePropertySpec<D, Meta>>> =
    mutableProperty(MetaConverter.meta, TypeIds.META, kind, read, write)
