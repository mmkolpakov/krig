package space.kscience.controls.connectivity

import space.kscience.controls.core.contracts.DeviceBlueprint

public val DeviceBlueprint<*>.composition: CompositionFeature?
    get() = this[CompositionSpec]

public val DeviceBlueprint<*>.connectivity: ConnectivityFeature?
    get() = this[ConnectivitySpec]

public val DeviceBlueprint<*>.childBindings: ChildBindingsFeature?
    get() = this[ChildBindingsSpec]