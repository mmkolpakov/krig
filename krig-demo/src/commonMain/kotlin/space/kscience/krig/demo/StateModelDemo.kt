package space.kscience.krig.demo

import space.kscience.krig.core.contracts.execute
import space.kscience.krig.core.contracts.read
import space.kscience.krig.core.contracts.write
import space.kscience.krig.dsl.device
import space.kscience.krig.dsl.stateModel

/** Virtual model with explicit state and typed access. */
suspend fun stateModelDemo() {
    val ctx = demoContext("state-model-demo")
    val pump = device("virtualPump", virtualPumpModel(), ctx) {
        blueprint(PumpBlueprint)
    }

    pump.write(PumpSpec.rpm, 900.0)

    println("=== State model ===")
    println("  rpm: ${pump.read(PumpSpec.rpm)}")
    println("  load: ${pump.read(PumpSpec.load)}")
    println("  command: ${pump.execute(PumpSpec.command, "sync")}")

    pump.close()
    ctx.close()
    println("\nDone - state model demo complete.")
}

private data class PumpState(
    var rpm: Double = 0.0,
)

private fun virtualPumpModel() = stateModel(::PumpState) {
    bind(
        PumpSpec.rpm,
        read = { rpm },
        write = { value -> rpm = value },
    )
    reader(PumpSpec.load) { rpm / 3_000.0 }
    action(PumpSpec.command) { command -> "model:$command@$rpm" }
}
