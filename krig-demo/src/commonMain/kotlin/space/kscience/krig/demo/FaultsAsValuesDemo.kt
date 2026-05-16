package space.kscience.krig.demo

import space.kscience.krig.api.result.DeviceOutcome
import space.kscience.krig.core.contracts.metaOf
import space.kscience.krig.dsl.device

/**
 * DeviceOutcome on the Meta/control-plane boundary: expected faults stay values.
 */
public suspend fun faultsAsValuesDemo() {
    val ctx = demoContext("faults-demo")
    val pump = device("faultyPump", pumpBackend(), ctx) {
        blueprint(PumpBlueprint)
    }

    println("=== Faults as values ===")
    when (val outcome = pump.writePropertyOutcome(PumpSpec.rpm.name, metaOf("fast"))) {
        is DeviceOutcome.Ok -> println("  unexpected write success")
        is DeviceOutcome.Fail -> println("  write rejected: ${outcome.fault.code}")
    }

    pump.close()
    ctx.close()
    println("\nDone - faults-as-values demo complete.")
}
