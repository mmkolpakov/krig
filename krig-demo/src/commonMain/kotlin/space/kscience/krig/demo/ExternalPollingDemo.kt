package space.kscience.krig.demo

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.yield
import space.kscience.dataforge.meta.MetaConverter
import space.kscience.dataforge.names.asName
import space.kscience.krig.api.data.isUsable
import space.kscience.krig.api.descriptors.TypeIds
import space.kscience.krig.api.result.ok
import space.kscience.krig.assembly.AcquisitionTagReader
import space.kscience.krig.assembly.dataAcquisition
import space.kscience.krig.assembly.pollTimer
import space.kscience.krig.core.contracts.metaOf
import space.kscience.krig.core.contracts.read
import space.kscience.krig.core.contracts.write
import space.kscience.krig.dsl.device
import space.kscience.krig.dsl.sharedTicks
import kotlin.time.Duration.Companion.milliseconds

/**
 * Protocol-neutral external polling: one shared timer drives multiple external tags.
 */
suspend fun externalPollingDemo(): Unit = supervisorScope {
    val ctx = demoContext("external-polling-demo")
    val pump = device("pollingPump", pumpBackend(), ctx) {
        blueprint(PumpBlueprint)
    }
    val registers = mutableMapOf(
        "rpm" to 1_000.0,
        "temperature" to 42.0,
    )
    val acquisition = dataAcquisition {
        source("stand", connector = "external.virtual")
        tag("rpm")
            .from("stand", "rpm", TypeIds.DOUBLE, timeout = 50.milliseconds)
            .toTarget(pump.name, PumpSpec.rpm.name)
        tag("temperature")
            .from("stand", "temperature", TypeIds.DOUBLE, timeout = 50.milliseconds)
            .withoutTarget()
        timer("fast", 10.milliseconds) {
            samples("rpm", "temperature")
        }
    }
    val ticks = sharedTicks(pump.deviceScope, 10.milliseconds)
    val reader = AcquisitionTagReader { tag ->
        ok(metaOf(registers.getValue(tag.address)))
    }

    println("=== External polling ===")
    val observations = async(start = CoroutineStart.UNDISPATCHED) {
        acquisition.pollTimer("fast", ticks, clock = pump.clock, reader = reader)
            .take(4)
            .toList()
            .also { values ->
                values.forEach { observation ->
                    val target = observation.tag.target
                    if (
                        target?.deviceId == pump.name &&
                        target.property == PumpSpec.rpm.name &&
                        observation.observed.isUsable
                    ) {
                        pump.write(PumpSpec.rpm, MetaConverter.double.read(observation.observed.value!!))
                    }
                }
            }
    }
    yield()
    val observed = observations.await()
    val temperatures = observed
        .filter { it.tag.id == "temperature".asName() }
        .mapNotNull { it.observed.value }
        .map { MetaConverter.double.read(it) }

    println("  polled tags: ${observed.map { it.tag.id }}")
    println("  pump rpm after polling: ${pump.read(PumpSpec.rpm)}")
    println("  external temperatures: $temperatures")

    pump.close()
    ctx.close()
    println("\nDone - external polling demo complete.")
}
