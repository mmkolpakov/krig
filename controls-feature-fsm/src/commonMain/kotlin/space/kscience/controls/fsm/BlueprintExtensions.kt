package space.kscience.controls.fsm

import space.kscience.controls.core.legacy_alpha_2.contracts.DeviceBlueprint

public val DeviceBlueprint<*>.lifecycleFeature: LifecycleFeature?
    get() = this[LifecycleFeature]

public val DeviceBlueprint<*>.operationalFsm: OperationalFsmFeature?
    get() = this[OperationalFsmFeature]