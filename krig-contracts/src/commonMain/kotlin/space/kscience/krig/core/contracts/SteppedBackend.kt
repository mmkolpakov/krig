package space.kscience.krig.core.contracts

import space.kscience.krig.core.UnstableKrigForSubclassing
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
