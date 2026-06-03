package space.kscience.krig.simulation

import kotlin.time.Duration
import kotlin.time.DurationUnit

/**
 * Autonomous dynamics `dy/dt = f(y)` for a coupled state vector, writing the derivative of [y]
 * into [into]. Pair with the vector [rungeKutta4] inside an `onStep` body.
 */
public fun interface Derivatives {
    public fun evaluate(y: DoubleArray, into: DoubleArray)
}

/**
 * Advances scalar state [y] over [dt] with classic 4th-order Runge–Kutta for `dy/dt = `[f]`(y)`.
 * Deterministic and allocation-free — the integrator KRig replay can reproduce bit-for-bit.
 */
public inline fun rungeKutta4(y: Double, dt: Duration, f: (Double) -> Double): Double {
    val h = dt.toDouble(DurationUnit.SECONDS)
    val k1 = f(y)
    val k2 = f(y + 0.5 * h * k1)
    val k3 = f(y + 0.5 * h * k2)
    val k4 = f(y + h * k3)
    return y + h * (k1 + 2.0 * k2 + 2.0 * k3 + k4) / 6.0
}

/** Advances state vector [y] in place over [dt] with classic 4th-order Runge–Kutta for system [f]. */
public fun rungeKutta4(y: DoubleArray, dt: Duration, f: Derivatives) {
    val h = dt.toDouble(DurationUnit.SECONDS)
    val n = y.size
    val k1 = DoubleArray(n)
    val k2 = DoubleArray(n)
    val k3 = DoubleArray(n)
    val k4 = DoubleArray(n)
    val stage = DoubleArray(n)

    f.evaluate(y, k1)
    for (i in 0 until n) stage[i] = y[i] + 0.5 * h * k1[i]
    f.evaluate(stage, k2)
    for (i in 0 until n) stage[i] = y[i] + 0.5 * h * k2[i]
    f.evaluate(stage, k3)
    for (i in 0 until n) stage[i] = y[i] + h * k3[i]
    f.evaluate(stage, k4)
    for (i in 0 until n) y[i] += h * (k1[i] + 2.0 * k2[i] + 2.0 * k3[i] + k4[i]) / 6.0
}
