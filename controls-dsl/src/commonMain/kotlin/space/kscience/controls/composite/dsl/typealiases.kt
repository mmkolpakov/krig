package space.kscience.controls.composite.dsl

import space.kscience.controls.core.legacy_alpha_2.meta.DeviceActionSpec
import space.kscience.controls.core.legacy_alpha_2.state.MutableDeviceState
import kotlin.properties.PropertyDelegateProvider
import kotlin.properties.ReadOnlyProperty

/**
 * A type alias for the complex, nested delegate provider used by [space.kscience.controls.composite.dsl.properties.stateProperty].
 *
 * This delegate employs a two-stage initialization pattern:
 * 1.  **Build-Time (Outer Provider):** When a `DeviceSpecification` is being configured, this part of the
 *     delegate registers the public-facing `MutableDevicePropertySpec` in the blueprint.
 * 2.  **Runtime (Inner Provider):** It returns another delegate provider. The runtime uses this inner
 *     provider on a concrete device instance to create or retrieve the actual, live `MutableDeviceState`.
 *
 * This complex structure is necessary to decouple the static blueprint definition from the stateful
 * runtime instance of a device.
 */
public typealias StatePropertyDelegate<D, T> =
        PropertyDelegateProvider<DeviceSpecification<D>, ReadOnlyProperty<DeviceSpecification<D>, PropertyDelegateProvider<Any?, ReadOnlyProperty<D, MutableDeviceState<T>>>>>

public typealias ActionDelegateProvider<D, I, O> = PropertyDelegateProvider<DeviceSpecification<D>, ReadOnlyProperty<DeviceSpecification<D>, DeviceActionSpec<D, I, O>>>