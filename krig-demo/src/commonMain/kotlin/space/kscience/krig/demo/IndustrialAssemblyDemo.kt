@file:OptIn(space.kscience.krig.core.UnstableKrigForSubclassing::class)

package space.kscience.krig.demo

import kotlin.reflect.KClass
import kotlin.time.Duration.Companion.milliseconds
import space.kscience.krig.api.features.DeviceFeatureSpec
import space.kscience.krig.api.spec.RetryPolicy
import space.kscience.krig.core.contracts.Device
import space.kscience.krig.core.contracts.DeviceBlueprint
import space.kscience.krig.core.contracts.DeviceFeatureInstaller
import space.kscience.krig.core.contracts.blueprintOf
import space.kscience.krig.core.contracts.execute
import space.kscience.krig.core.contracts.read
import space.kscience.krig.core.contracts.sampling.doubleSampler
import space.kscience.krig.core.contracts.typed.typedBackend
import space.kscience.krig.core.contracts.write
import space.kscience.krig.core.meta.DeviceSpecBuilder
import space.kscience.krig.core.pipeline.TypedPipelineBuilder
import space.kscience.krig.dsl.device
import space.kscience.dataforge.meta.MetaConverter

/**
 * Spec-first industrial assembly: reusable blueprint, explicit backend, typed access.
 */
public suspend fun industrialAssemblyDemo() {
    val ctx = demoContext("industrial-demo")
    val pump = device("mainPump", pumpBackend(), ctx) {
        blueprint(PumpBlueprint)
        install(DemoRetryInstaller) {
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

public object PumpSpec : DeviceSpecBuilder<Device>() {
    val rpm by mutableDoubleProperty(
        read = { error("pumpBackend supplies the typed rpm reader") },
        write = { error("pumpBackend supplies the typed rpm writer") },
    )
    val load by doubleProperty {
        error("pumpBackend supplies the typed load reader")
    }
    val command by action(MetaConverter.string, MetaConverter.string) { input ->
        input
    }
}

public val PumpBlueprint: DeviceBlueprint<Device> = blueprintOf(
    id = "space.kscience.krig.demo.pump",
    spec = PumpSpec,
    version = "1.0.0-alpha-3",
)

public fun pumpBackend() = typedBackend {
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

internal object DemoRetryInstaller : DeviceFeatureInstaller<DemoRetryConfig, DeviceFeatureSpec> {
    override val id: String = "demo.retry"
    override val featureClass: KClass<DeviceFeatureSpec> = DeviceFeatureSpec::class
    override fun createConfig(): DemoRetryConfig = DemoRetryConfig()

    override fun install(config: DemoRetryConfig, pipeline: TypedPipelineBuilder) {
        pipeline.readDefaultRetry = config.policy
        pipeline.writeDefaultRetry = config.policy
        pipeline.actionDefaultRetry = config.policy
    }
}
