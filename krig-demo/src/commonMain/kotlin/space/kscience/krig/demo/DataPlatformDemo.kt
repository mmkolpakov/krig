package space.kscience.krig.demo

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.transform
import kotlinx.serialization.json.Json
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.MetaConverter
import space.kscience.dataforge.names.parseAsName
import space.kscience.krig.assembly.DataPlatformConfiguration
import space.kscience.krig.assembly.PropertySpec
import space.kscience.krig.assembly.dataPlatform
import space.kscience.krig.assembly.validate
import space.kscience.krig.core.PerformancePitfall
import space.kscience.krig.core.contracts.Device
import space.kscience.krig.core.contracts.write
import space.kscience.krig.dsl.device
import kotlin.time.Duration.Companion.milliseconds

private val demoPlatformJson = Json { encodeDefaults = false }

/**
 * Declarative platform map executed against a live typed device.
 */
@OptIn(PerformancePitfall::class)
suspend fun dataPlatformDemo() {
    val ctx = demoContext("data-platform-demo")
    val pump = device("mainPump", pumpBackend(), ctx) {
        blueprint(PumpBlueprint)
    }
    val platform = dataPlatform {
        source("mainPump") from PumpBlueprint.id.value
        property("mainPump.rpm")
            .from("mainPump", PumpSpec.rpm.name.toString(), reduce = "LastValue")
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
    val samples = platform.sampleTimer(mapOf("mainPump" to pump), ticks).toList()
    val json = demoPlatformJson.encodeToString(DataPlatformConfiguration.serializer(), platform)

    println("=== Data platform ===")
    println("  sources: ${platform.sources.map { it.id }}")
    println("  timer properties: ${platform.timers.single().properties}")
    println("  validation errors: ${platform.validate().size}")
    println("  sampled properties: ${samples.map { it.property.id }}")
    println("  sampled rpm: ${samples.map { MetaConverter.double.read(it.value) }}")
    println("  descriptor JSON chars: ${json.length}")
    pump.close()
    ctx.close()
    println("\nDone - data platform demo complete.")
}

private data class PlatformSample(
    val property: PropertySpec,
    val value: Meta,
)

@OptIn(PerformancePitfall::class)
private fun DataPlatformConfiguration.sampleTimer(
    devices: Map<String, Device>,
    ticks: Flow<Unit>,
): Flow<PlatformSample> {
    val timer = timers.single()
    val propertiesById = properties.associateBy { it.id }
    return ticks.transform {
        for (propertyId in timer.properties) {
            val property = propertiesById.getValue(propertyId)
            val device = devices.getValue(property.sourceId)
            emit(PlatformSample(property, device.readProperty(property.property.parseAsName())))
        }
    }
}
