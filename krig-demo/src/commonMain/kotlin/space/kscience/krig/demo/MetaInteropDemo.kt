package space.kscience.krig.demo

import space.kscience.dataforge.meta.MetaConverter
import space.kscience.dataforge.meta.toJson
import space.kscience.krig.api.faults.displayType
import space.kscience.krig.api.result.OperationOutcome
import space.kscience.krig.core.PerformancePitfall
import space.kscience.krig.core.contracts.metaOf
import space.kscience.krig.core.contracts.read
import space.kscience.krig.core.contracts.readProperty
import space.kscience.krig.core.contracts.write
import space.kscience.krig.core.contracts.writeProperty
import space.kscience.krig.dsl.device

/**
 * Meta/JSON boundary beside the typed hot path.
 */
@OptIn(PerformancePitfall::class)
suspend fun metaInteropDemo() {
    val ctx = demoContext("meta-interop-demo")
    val pump = device("interopPump", pumpBackend(), ctx) {
        manifest(PumpManifest)
    }

    println("=== Meta interop ===")
    val boundaryValue = metaOf(1_200.0)
    println("  Meta path: writeProperty/readProperty")
    pump.writeProperty(PumpSpec.rpm.name, boundaryValue)
    val boundaryRead = pump.readProperty(PumpSpec.rpm.name)
    println("    rpm: ${MetaConverter.double.read(boundaryRead)}")
    println("  JSON payload: ${boundaryValue.toJson()}")

    println("  Typed path: write(spec)/read(spec)")
    pump.write(PumpSpec.rpm, 1_250.0)
    println("    rpm: ${pump.read(PumpSpec.rpm)}")

    when (val rejected = pump.writePropertyOutcome(PumpSpec.rpm.name, metaOf("fast"))) {
        is OperationOutcome.Ok -> println("  unexpected invalid Meta write success")
        is OperationOutcome.Fail -> println("  invalid Meta rejected: ${rejected.fault.displayType}")
    }

    pump.close()
    ctx.close()
    println("\nDone - Meta interop demo complete.")
}
