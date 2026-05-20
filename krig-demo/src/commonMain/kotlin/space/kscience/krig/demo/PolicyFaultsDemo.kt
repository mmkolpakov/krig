package space.kscience.krig.demo

import kotlin.time.Duration.Companion.milliseconds
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName
import space.kscience.krig.api.faults.InvalidStateFault
import space.kscience.krig.api.result.OperationOutcome
import space.kscience.krig.api.result.toOutcome
import space.kscience.krig.core.contracts.metaOf
import space.kscience.krig.core.capabilities.Capability
import space.kscience.krig.core.capabilities.CapabilityKey
import space.kscience.krig.dsl.device
import space.kscience.krig.dsl.feature
import space.kscience.krig.dsl.write

/**
 * Policy gates and expected faults as values on the control-plane boundary.
 */
suspend fun policyFaultsDemo() {
    val ctx = demoContext("policy-faults-demo")
    val pump = device("policyPump", pumpBackend(), ctx) {
        blueprint(PumpBlueprint)
        install(DemoSafety)
    }
    val safety = pump.capability(DemoSafetyCapability)
        ?: error("DemoSafetyCapability was not installed")

    println("=== Policy and faults ===")
    when (val denied = pump.writePropertyOutcome(PumpSpec.rpm.name, metaOf(800.0))) {
        is OperationOutcome.Ok -> println("  unexpected locked write success")
        is OperationOutcome.Fail -> println("  gate denied: ${denied.fault.faultType}")
    }

    safety.state.writesEnabled = true
    when (val invalid = pump.writePropertyOutcome(PumpSpec.rpm.name, metaOf("fast"))) {
        is OperationOutcome.Ok -> println("  unexpected invalid Meta write success")
        is OperationOutcome.Fail -> println("  invalid Meta rejected: ${invalid.fault.faultType}")
    }

    val accepted = pump.writePropertyOutcome(PumpSpec.rpm.name, metaOf(800.0))
    println("  accepted write: ${accepted is OperationOutcome.Ok}")
    println("  observed faults: ${safety.state.writeFaults}")

    pump.close()
    ctx.close()
    println("\nDone - policy/faults demo complete.")
}

private class DemoSafetyConfig {
    var writesEnabled: Boolean = false
}

private class DemoSafetyState(
    var writesEnabled: Boolean,
    val writeFaults: MutableList<Name> = mutableListOf(),
)

private class DemoSafetyCapability(initialWritesEnabled: Boolean) : Capability<DemoSafetyState> {
    override val key: CapabilityKey<*> get() = DemoSafetyCapability
    override val state: DemoSafetyState = DemoSafetyState(initialWritesEnabled)

    companion object Key : CapabilityKey<DemoSafetyCapability> {
        override val id: Name = "demo.safety".asName()
    }
}

private val DemoSafety = feature("demo.safety", ::DemoSafetyConfig) {
    val safety = DemoSafetyCapability(config.writesEnabled)
    capability(safety)
    write {
        timeout = 20.milliseconds
        gate { operation ->
            if (safety.state.writesEnabled) {
                OperationOutcome.OkUnit
            } else {
                InvalidStateFault(
                    currentState = "Locked",
                    requiredState = "Unlocked",
                    operation = "write '${operation.name}'",
                ).toOutcome()
            }
        }
        observe { _, _, fault ->
            if (fault != null) safety.state.writeFaults += fault.faultType
        }
    }
}
