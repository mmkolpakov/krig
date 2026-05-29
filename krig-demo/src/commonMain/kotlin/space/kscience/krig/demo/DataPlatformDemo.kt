package space.kscience.krig.demo

import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import space.kscience.dataforge.meta.MetaConverter
import space.kscience.dataforge.names.asName
import space.kscience.dataforge.names.toStringUnescaped
import space.kscience.krig.assembly.ReductionSpec
import space.kscience.krig.assembly.dataPlatform
import space.kscience.krig.assembly.pollTimer
import space.kscience.krig.assembly.runtime
import space.kscience.krig.assembly.toMeta
import space.kscience.krig.assembly.validate
import space.kscience.krig.core.contracts.write
import space.kscience.krig.dsl.device
import kotlin.time.Duration.Companion.milliseconds

/**
 * Declarative platform map executed against a live device.
 */
suspend fun dataPlatformDemo() {
    val ctx = demoContext("data-platform-demo")
    val pump = device("mainPump", pumpBackend(), ctx) {
        manifest(PumpManifest)
    }
    val platform = dataPlatform {
        source("mainPump") from PumpManifest.id.toString()
        property("mainPump.rpm")
            .from("mainPump", PumpSpec.rpm.name.toString(), reduction = ReductionSpec.Last)
        timer("fast", 50.milliseconds) {
            samples("mainPump.rpm")
        }
    }
    val ticks = flow {
        pump.write(PumpSpec.rpm, 700.0)
        emit(Unit)
        pump.write(PumpSpec.rpm, 1_100.0)
        emit(Unit)
    }
    val runtime = platform.runtime(mapOf("mainPump".asName() to pump), clock = pump.clock)
    val samples = runtime.pollTimer("fast", ticks).toList()
    val descriptorMeta = platform.toMeta()

    println("=== Data platform ===")
    println("  sources: ${platform.sources.map { it.id }}")
    println("  timer properties: ${platform.timers.single().properties.map { it.toStringUnescaped() }}")
    println("  validation errors: ${platform.validate().size}")
    println("  sampled properties: ${samples.map { it.property.id.toStringUnescaped() }}")
    val rpmValues = samples.map { sample ->
        MetaConverter.double.read(sample.observed.value ?: error("missing sample"))
    }
    println("  sampled rpm: $rpmValues")
    println("  descriptor meta nodes: ${descriptorMeta.items.size}")
    pump.close()
    ctx.close()
    println("\nDone - data platform demo complete.")
}
