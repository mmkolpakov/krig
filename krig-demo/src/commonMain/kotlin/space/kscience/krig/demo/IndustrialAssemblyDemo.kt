package space.kscience.krig.demo

import kotlin.time.Duration.Companion.milliseconds
import space.kscience.krig.api.descriptors.attributes.RetryPolicy
import space.kscience.krig.core.contracts.Device
import space.kscience.krig.core.contracts.DeviceBlueprint
import space.kscience.krig.core.contracts.blueprintOf
import space.kscience.krig.core.contracts.execute
import space.kscience.krig.core.contracts.read
import space.kscience.krig.core.contracts.sampling.doubleSampler
import space.kscience.krig.core.contracts.typed.backend
import space.kscience.krig.core.contracts.write
import space.kscience.krig.core.meta.doubleProperty
import space.kscience.krig.core.meta.DeviceContractBuilder
import space.kscience.krig.core.meta.mutableDoubleProperty
import space.kscience.krig.dsl.device
import space.kscience.krig.dsl.feature
import space.kscience.krig.dsl.retryReadsWritesAndActions
import space.kscience.dataforge.meta.MetaConverter

/**
 * Spec-first industrial assembly: reusable blueprint, explicit backend, typed access.
 */
suspend fun industrialAssemblyDemo() {
    val ctx = demoContext("industrial-demo")
    val pump = device("mainPump", pumpBackend(), ctx) {
        blueprint(PumpBlueprint)
        install(DemoRetry) {
            policy = RetryPolicy(maxAttempts = 2, initialDelay = 10.milliseconds)
        }
    }

    pump.write(PumpSpec.rpm, 1_200.0)
    println("=== Industrial assembly ===")
    println("  rpm: ${pump.read(PumpSpec.rpm)}")
    println("  load: ${pump.read(PumpSpec.load)}")
    println("  command: ${pump.execute(PumpSpec.command, "prime")}")

    pump.close()
    ctx.close()
    println("\nDone - industrial assembly demo complete.")
}

object PumpSpec : DeviceContractBuilder() {
    val rpm by mutableDoubleProperty()
    val load by doubleProperty()
    val command by action(MetaConverter.string, MetaConverter.string)
}

val PumpBlueprint: DeviceBlueprint<Device> = blueprintOf(
    id = "space.kscience.krig.demo.pump",
    contract = PumpSpec,
    version = "1.0.0-alpha-3",
)

fun pumpBackend() = backend {
    var rpm = 0.0
    val rpmSampler = doubleSampler(capacity = 64)

    reader(PumpSpec.rpm) { rpm }
    writer(PumpSpec.rpm) { value ->
        rpm = value
        rpmSampler.publishDouble(value)
    }
    sampler(PumpSpec.rpm) { rpmSampler }

    reader(PumpSpec.load) { rpm / 3_000.0 }
    action(PumpSpec.command) { command -> "ack:$command" }
}

internal class DemoRetryConfig {
    var policy: RetryPolicy = RetryPolicy(maxAttempts = 1, initialDelay = 10.milliseconds)
}

internal val DemoRetry = feature("demo.retry", ::DemoRetryConfig) {
    retryReadsWritesAndActions(config.policy)
}
