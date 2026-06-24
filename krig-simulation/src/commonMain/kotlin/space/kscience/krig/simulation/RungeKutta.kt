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

/**
 * Pre-allocated scratch for the vector [rungeKutta4]. Allocate once (sized to the state vector) and
 * reuse on every `step()`, so a high-rate simulation tick does not allocate the five stage arrays per
 * call. The arithmetic is identical to the allocating overload, so bit-for-bit replay is preserved.
 */
public class RungeKuttaWorkspace(size: Int) {
    init { require(size > 0) { "RungeKuttaWorkspace size must be positive, got $size." } }

    internal val k1: DoubleArray = DoubleArray(size)
    internal val k2: DoubleArray = DoubleArray(size)
    internal val k3: DoubleArray = DoubleArray(size)
    internal val k4: DoubleArray = DoubleArray(size)
    internal val stage: DoubleArray = DoubleArray(size)

    public val size: Int get() = k1.size
}

/**
 * Advances state vector [y] in place over [dt] with classic 4th-order Runge–Kutta for system [f],
 * reusing the caller-owned [workspace] for all stage buffers — allocation-free per call.
 */
public fun rungeKutta4(y: DoubleArray, dt: Duration, f: Derivatives, workspace: RungeKuttaWorkspace) {
    require(workspace.size == y.size) {
        "RungeKuttaWorkspace size ${workspace.size} does not match state vector size ${y.size}."
    }
    val h = dt.toDouble(DurationUnit.SECONDS)
    val n = y.size
    val k1 = workspace.k1
    val k2 = workspace.k2
    val k3 = workspace.k3
    val k4 = workspace.k4
    val stage = workspace.stage

    f.evaluate(y, k1)
    for (i in 0 until n) stage[i] = y[i] + 0.5 * h * k1[i]
    f.evaluate(stage, k2)
    for (i in 0 until n) stage[i] = y[i] + 0.5 * h * k2[i]
    f.evaluate(stage, k3)
    for (i in 0 until n) stage[i] = y[i] + h * k3[i]
    f.evaluate(stage, k4)
    for (i in 0 until n) y[i] += h * (k1[i] + 2.0 * k2[i] + 2.0 * k3[i] + k4[i]) / 6.0
}

/**
 * Allocating convenience overload: advances state vector [y] in place over [dt] with classic
 * 4th-order Runge–Kutta. Allocates a fresh [RungeKuttaWorkspace] per call; for a hot simulation loop
 * allocate one workspace and call the [workspace]-taking overload instead.
 */
public fun rungeKutta4(y: DoubleArray, dt: Duration, f: Derivatives): Unit =
    rungeKutta4(y, dt, f, RungeKuttaWorkspace(y.size))
