package space.kscience.krig.demo

import space.kscience.dataforge.meta.MetaConverter
import space.kscience.krig.core.contracts.steppedBackend
import space.kscience.krig.simulation.rungeKutta4
import kotlin.math.round
import kotlin.math.sqrt
import kotlin.time.Duration.Companion.milliseconds

/**
 * Digital twin driven by [rungeKutta4] inside `onStep`: a draining tank `dL/dt = inflow − k·√L`
 * (Torricelli, no elementary closed form), integrated deterministically on each simulation tick.
 */
suspend fun digitalTwinDemo() {
    val inflow = 2.0
    val drain = 1.0
    var level = 0.0
    val tank = steppedBackend {
        val levelProperty = readable("level", initial = 0.0, converter = MetaConverter.double)
        onStep { dt ->
            level = rungeKutta4(level, dt) { l -> inflow - drain * sqrt(maxOf(0.0, l)) }
            levelProperty.value = level
        }
    }
    repeat(3_000) { tank.step(10.milliseconds) }
    tank.close()

    val steadyState = (inflow / drain) * (inflow / drain)
    println("=== Digital twin (RK4) ===")
    println("  tank level after 30s: ${round3(level)} (steady-state ${round3(steadyState)})")
    println("\nDone - digital twin demo complete.")
}

private fun round3(value: Double): Double = round(value * 1_000.0) / 1_000.0
