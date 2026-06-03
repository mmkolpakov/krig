package space.kscience.krig.demo

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import space.kscience.dataforge.names.asName
import space.kscience.dataforge.names.toStringUnescaped
import space.kscience.krig.api.result.getOrThrow
import space.kscience.krig.assembly.AcquisitionConnectors
import space.kscience.krig.assembly.acquisitionReader
import space.kscience.krig.assembly.dataAcquisition
import space.kscience.krig.assembly.flattenDevices
import space.kscience.krig.assembly.observations
import space.kscience.krig.assembly.readAt
import space.kscience.krig.assembly.runner
import space.kscience.krig.assembly.validate
import space.kscience.krig.core.contracts.write
import space.kscience.krig.dsl.device
import space.kscience.krig.dsl.deviceGroup
import kotlin.time.Duration.Companion.milliseconds

/**
 * Declarative acquisition straight over a `deviceGroup { }` hierarchy: the same tree that owns the
 * devices also addresses them for sampling. [acquisitionReader] flattens the tree to dotted paths
 * (`plant.lineA.main`), [runner]/[observations] drive every timer from one clock, and [readAt] gives
 * a typed point read by path — no hand-built `mapOf(... .asName())`.
 */
suspend fun deviceTreeAcquisitionDemo() {
    val ctx = demoContext("device-tree-acquisition-demo")
    val mainPump = device("main", pumpBackend(), ctx) { manifest(PumpManifest) }
    val reservePump = device("reserve", pumpBackend(), ctx) { manifest(PumpManifest) }

    val plant = deviceGroup {
        deviceGroup("plant") {
            deviceGroup("lineA") { device("main", mainPump) }
            deviceGroup("lineB") { device("reserve", reservePump) }
        }
    }.buildAndStart("site", ctx)

    mainPump.write(PumpSpec.rpm, 700.0)
    reservePump.write(PumpSpec.rpm, 1_500.0)

    val acquisition = dataAcquisition {
        source("plant.lineA.main", AcquisitionConnectors.KrigDevice)
        source("plant.lineB.reserve", AcquisitionConnectors.KrigDevice)
        tag("mainRpm").from("plant.lineA.main", PumpSpec.rpm.name.toString())
        tag("reserveRpm").from("plant.lineB.reserve", PumpSpec.rpm.name.toString())
        timer("fast", 50.milliseconds) { samples("mainRpm", "reserveRpm") }
    }

    // One tick of the merged runner emits both tags, sampled through the live device tree.
    val samples = acquisition.runner(plant.acquisitionReader())
        .observations()
        .take(2)
        .toList()

    println("=== Device-tree acquisition (deviceGroup -> runner) ===")
    println("  addressable paths: ${plant.flattenDevices().keys.map { it.toStringUnescaped() }}")
    println("  validation errors: ${acquisition.validate().size}")
    println("  readAt(plant.lineA.main): ${plant.readAt("plant.lineA.main".asName(), PumpSpec.rpm).getOrThrow()}")
    println("  sampled tags: ${samples.map { it.spec.id.toStringUnescaped() }}")
    println("  sampled rpm: ${samples.map { sample -> sample.observed.value?.let { PumpSpec.rpm.converter.read(it) } }}")

    plant.close()
    ctx.close()
    println("\nDone - device-tree acquisition demo complete.")
}
