package space.kscience.krig.demo

import kotlin.reflect.KClass
import kotlin.time.Duration.Companion.milliseconds
import space.kscience.krig.api.features.DeviceFeatureSpec
import space.kscience.krig.api.faults.InvalidStateFault
import space.kscience.krig.api.result.DeviceOutcome
import space.kscience.krig.api.result.toOutcome
import space.kscience.krig.core.contracts.DeviceFeatureInstaller
import space.kscience.krig.core.contracts.metaOf
import space.kscience.krig.core.pipeline.TypedPipelineBuilder
import space.kscience.krig.dsl.device

/**
 * Policy gates and expected faults as values on the control-plane boundary.
 */
suspend fun policyFaultsDemo() {
    val ctx = demoContext("policy-faults-demo")
    val safety = DemoSafetyConfig()
    val pump = device("policyPump", pumpBackend(), ctx) {
        blueprint(PumpBlueprint)
        install(demoSafetyInstaller(safety))
    }

    println("=== Policy and faults ===")
    when (val denied = pump.writePropertyOutcome(PumpSpec.rpm.name, metaOf(800.0))) {
        is DeviceOutcome.Ok -> println("  unexpected locked write success")
        is DeviceOutcome.Fail -> println("  gate denied: ${denied.fault.code}")
    }

    safety.writesEnabled = true
    when (val invalid = pump.writePropertyOutcome(PumpSpec.rpm.name, metaOf("fast"))) {
        is DeviceOutcome.Ok -> println("  unexpected invalid Meta write success")
        is DeviceOutcome.Fail -> println("  invalid Meta rejected: ${invalid.fault.code}")
    }

    val accepted = pump.writePropertyOutcome(PumpSpec.rpm.name, metaOf(800.0))
    println("  accepted write: ${accepted is DeviceOutcome.Ok}")
    println("  observed faults: ${safety.writeFaults}")

    pump.close()
    ctx.close()
    println("\nDone - policy/faults demo complete.")
}

private class DemoSafetyConfig {
    var writesEnabled: Boolean = false
    val writeFaults: MutableList<String> = mutableListOf()
}

private fun demoSafetyInstaller(config: DemoSafetyConfig): DeviceFeatureInstaller<DemoSafetyConfig, DeviceFeatureSpec> =
    object : DeviceFeatureInstaller<DemoSafetyConfig, DeviceFeatureSpec> {
        override val id: String = "demo.safety"
        override val featureClass: KClass<DeviceFeatureSpec> = DeviceFeatureSpec::class
        override fun createConfig(): DemoSafetyConfig = config

        override fun install(config: DemoSafetyConfig, pipeline: TypedPipelineBuilder) {
            pipeline.writeDefaultTimeout = 20.milliseconds
            pipeline.addWriteGate { spec ->
                if (config.writesEnabled) {
                    DeviceOutcome.OkUnit
                } else {
                    InvalidStateFault(
                        currentState = "Locked",
                        requiredState = "Unlocked",
                        operation = "write '${spec.name}'",
                    ).toOutcome()
                }
            }
            pipeline.addWriteObserver { _, _, fault ->
                if (fault != null) config.writeFaults += fault.code
            }
        }
    }
