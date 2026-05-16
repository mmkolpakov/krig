package space.kscience.krig.demo

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import space.kscience.krig.api.context.AnonymousPrincipal
import space.kscience.krig.api.expressions.Expression
import space.kscience.krig.core.PerformancePitfall
import space.kscience.krig.core.contracts.Device
import space.kscience.krig.core.expressions.ExpressionContext
import space.kscience.krig.core.expressions.compile
import space.kscience.krig.core.state.DeviceState
import space.kscience.krig.dsl.device
import space.kscience.krig.dsl.plus
import space.kscience.krig.dsl.ref
import space.kscience.krig.dsl.times
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName

/**
 * Expression system walkthrough: build a computation tree and compile it to reactive state.
 *
 * Run: `./gradlew :krig-demo:jvmRun`
 */
@OptIn(PerformancePitfall::class)
public suspend fun expressionDemo() {
    val ctx = demoContext("expr-demo")

    println("=== Expression tree ===")

    val sensorA: Device = device("sensorA", ctx) { property("value") { 10.0 } }
    val sensorB: Device = device("sensorB", ctx) { property("value") { 20.0 } }

    val a: Expression<Double> = ref("sensorA", "value")
    val b: Expression<Double> = ref("sensorB", "value")
    val formula: Expression<Double> = a * 2.0 + b + 1.0
    println("  tree: $formula")

    val devices: Map<Name, Device> = mapOf(
        "sensorA".asName() to sensorA,
        "sensorB".asName() to sensorB,
    )
    val expressionScope = CoroutineScope(currentCoroutineContext() + SupervisorJob())
    try {
        val exprCtx = ExpressionContext.from(expressionScope, devices, AnonymousPrincipal)
        val computed: DeviceState<Double> = formula.compile(exprCtx)
        val init = computed.stateFlow.first()
        println("  compiled = 10*2 + 20 + 1 = ${init.value}")
    } finally {
        expressionScope.cancel()
        sensorA.close()
        sensorB.close()
        ctx.close()
    }

    println("\nDone - expression demo complete.")
}
