package space.kscience.krig.core.contracts

import space.kscience.krig.core.UnstableKrigForSubclassing
import space.kscience.krig.core.contracts.typed.TypedDeviceBackend
import kotlin.time.Duration

/**
 * Marks a [DeviceBackend] whose state advances in discrete time steps — ODE integrators,
 * PID controllers, digital-twin shadows, co-simulation federates. Simulation
 * schedulers call [step] on every tick.
 */
@SubclassOptInRequired(UnstableKrigForSubclassing::class)
public interface SteppedBackend : DeviceBackend {
    public fun step(dt: Duration)
}

/** Stepped backend that keeps the typed contract surface visible to callers. */
@OptIn(UnstableKrigForSubclassing::class)
public interface TypedSteppedBackend : SteppedBackend, TypedDeviceBackend
