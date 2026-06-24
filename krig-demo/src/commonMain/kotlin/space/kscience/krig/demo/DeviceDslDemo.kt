package space.kscience.krig.demo

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.yield
import kotlin.time.Instant
import space.kscience.krig.api.messages.PropertyChangedMessage
import space.kscience.krig.core.KrigPerformancePitfall
import space.kscience.krig.core.contracts.Device
import space.kscience.krig.core.contracts.metaOf
import space.kscience.krig.core.contracts.readProperty
import space.kscience.krig.core.contracts.writeProperty
import space.kscience.krig.core.state.collectPropertyHistory
import space.kscience.krig.dsl.device
import space.kscience.krig.dsl.noResult
import space.kscience.dataforge.meta.MetaConverter
import space.kscience.dataforge.names.asName

/**
 * Device scripting DSL for notebooks and compact local experiments.
 *
 * Run: `./gradlew :krig-demo:jvmRun`
 */
@OptIn(KrigPerformancePitfall::class)
suspend fun deviceDslDemo(): Unit = coroutineScope {
    val ctx = demoContext("demo")

    println("=== Device scripting DSL ===")
    val thermo: Device = device("thermo", ctx) {
        propertyDouble("sensor") { 23.5 }
        propertyDouble("compensated") {
            readDouble("sensor") + 0.25
        }
        mutableProperty("setpoint", initial = 20.0)
        action("reset") { _ ->
            writeDouble("setpoint", 20.0)
            noResult
        }
    }
    println("  sensor: ${thermo.readProperty("sensor".asName())}")
    println("  compensated: ${thermo.readProperty("compensated".asName())}")
    thermo.writeProperty("setpoint".asName(), metaOf(25.0))
    println("  setpoint <- 25.0")

    println("\n=== Property history ===")
    val history = collectPropertyHistory(
        scope = thermo.deviceScope,
        messages = flowOf(
            PropertyChangedMessage(
                time = Instant.fromEpochMilliseconds(1_000),
                property = "setpoint".asName(),
                value = metaOf(26.0),
                sourceDevice = thermo.name,
            ),
            PropertyChangedMessage(
                time = Instant.fromEpochMilliseconds(2_000),
                property = "setpoint".asName(),
                value = metaOf(27.0),
                sourceDevice = thermo.name,
            ),
        ),
        deviceName = thermo.name,
        propertyName = "setpoint".asName(),
        converter = MetaConverter.double,
    )
    val samplesDeferred = async(start = CoroutineStart.UNDISPATCHED) {
        history.flowHistory(Instant.DISTANT_PAST, Instant.DISTANT_FUTURE).take(2).toList()
    }
    yield()
    val samples = samplesDeferred.await()
    println("  recent setpoint values: ${samples.map { it.value }}")

    thermo.close()
    ctx.close()
    println("\nDone - Device scripting DSL demo complete.")
}
