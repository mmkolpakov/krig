package space.kscience.controls.fsm

import space.kscience.controls.core.contracts.DeviceBlueprint

/**
 * Access to the [LifecycleFeature] DTO of the blueprint using the typed [LifecycleSpec].
 */
public val DeviceBlueprint<*>.lifecycleFeature: LifecycleFeature?
    get() = this[LifecycleSpec]

/**
 * Access to the [OperationalFsmFeature] DTO of the blueprint using the typed [OperationalFsmSpec].
 */
public val DeviceBlueprint<*>.operationalFsm: OperationalFsmFeature?
    get() = this[OperationalFsmSpec]