package space.kscience.krig.simulation

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class RungeKuttaTest {

    @Test
    fun scalarMatchesFirstOrderLag() {
        val tau = 0.5
        val steady = 10.0
        var y = 0.0
        repeat(500) { y = rungeKutta4(y, 10.milliseconds) { (steady - it) / tau } }
        val analytic = steady + (0.0 - steady) * exp(-5.0 / tau)
        assertTrue(abs(y - analytic) < 1e-5, "RK4 should track the analytic lag, got $y vs $analytic")
    }

    @Test
    fun vectorConservesHarmonicOscillatorOverPeriod() {
        val omega = 2.0 * PI
        val state = doubleArrayOf(1.0, 0.0)
        val system = Derivatives { y, into ->
            into[0] = y[1]
            into[1] = -omega * omega * y[0]
        }
        repeat(1_000) { rungeKutta4(state, 1.milliseconds, system) }
        assertTrue(abs(state[0] - 1.0) < 1e-3, "position should return after one period, got ${state[0]}")
        assertTrue(abs(state[1]) < 1e-2, "velocity should return near zero, got ${state[1]}")
    }
}
