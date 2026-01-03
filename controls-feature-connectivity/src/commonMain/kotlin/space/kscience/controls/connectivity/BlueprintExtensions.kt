package space.kscience.controls.connectivity

import space.kscience.controls.core.contracts.DeviceBlueprint

public val DeviceBlueprint<*>.composition: CompositionFeature?
    get() = this[CompositionFeature]

public val DeviceBlueprint<*>.connectivity: ConnectivityFeature?
    get() = this[ConnectivityFeature]

public val DeviceBlueprint<*>.childBindings: ChildBindingsFeature?
    get() = this[ChildBindingsFeature]