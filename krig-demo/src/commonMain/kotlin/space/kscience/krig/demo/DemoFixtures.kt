package space.kscience.krig.demo

import kotlinx.coroutines.delay
import space.kscience.dataforge.meta.MetaConverter
import space.kscience.krig.api.descriptors.attributes.RetryPolicy
import space.kscience.krig.api.faults.OperationFaultException
import space.kscience.krig.api.faults.TimeoutFault
import space.kscience.krig.core.contracts.DeviceManifest
import space.kscience.krig.core.contracts.manifestOf
import space.kscience.krig.core.contracts.sampling.doubleSampler
import space.kscience.krig.core.contracts.deviceBackend
import space.kscience.krig.core.meta.DeviceContractBuilder
import space.kscience.krig.core.meta.doubleProperty
import space.kscience.krig.core.meta.mutableDoubleProperty
import space.kscience.krig.dsl.pipelineFeature
import space.kscience.krig.dsl.retryReadsWritesAndActions
import kotlin.time.Duration.Companion.milliseconds

/**
 * Shared demo fixtures: the canonical pump contract, manifest, in-memory backends, and the
 * retry pipeline feature reused across demo scenarios. Keeps individual demos thin.
 */
object PumpSpec : DeviceContractBuilder() {
    val rpm by mutableDoubleProperty()
    val load by doubleProperty()
    val command by action(MetaConverter.string, MetaConverter.string)
}

val PumpManifest: DeviceManifest = manifestOf(
    id = "space.kscience.krig.demo.pump",
    contract = PumpSpec,
    version = "1.0.0-alpha-3",
)

/** Plain in-memory pump backend with an unboxed rpm sampler. */
fun pumpBackend() = deviceBackend {
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

/**
 * Pump backend that fails the first [failuresBeforeSuccess] rpm reads with a transient
 * [TimeoutFault], then succeeds. [attempts] exposes the running read count for assertions.
 */
class FlakyPump(private val failuresBeforeSuccess: Int = 2) {
    var attempts: Int = 0
        private set

    fun backend() = deviceBackend {
        var rpm = 1_200.0

        reader(PumpSpec.rpm) {
            attempts += 1
            delay(10.milliseconds)
            if (attempts <= failuresBeforeSuccess) {
                throw OperationFaultException(TimeoutFault())
            }
            rpm
        }
        writer(PumpSpec.rpm) { value -> rpm = value }
        reader(PumpSpec.load) { rpm / 3_000.0 }
        action(PumpSpec.command) { command -> "ack:$command" }
    }
}

internal class DemoRetryConfig {
    var policy: RetryPolicy = RetryPolicy(maxAttempts = 1, initialDelay = 10.milliseconds)
}

internal val DemoRetry = pipelineFeature("demo.retry", ::DemoRetryConfig) {
    retryReadsWritesAndActions(config.policy)
}
