package space.kscience.controls.connectivity

import space.kscience.controls.core.legacy_alpha_2.contracts.DeviceBlueprint

public val DeviceBlueprint<*>.composition: CompositionFeature?
    get() = this[CompositionFeature]

public val DeviceBlueprint<*>.connectivity: ConnectivityFeature?
    get() = this[ConnectivityFeature]

public val DeviceBlueprint<*>.childBindings: ChildBindingsFeature?
    get() = this[ChildBindingsFeature]