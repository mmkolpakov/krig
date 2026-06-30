package space.kscience.krig.demo

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import space.kscience.dataforge.names.Name
import space.kscience.krig.api.data.QualitySeverity
import space.kscience.krig.api.descriptors.TypeIds
import space.kscience.krig.api.faults.TransportFault
import space.kscience.krig.api.result.OperationOutcome
import space.kscience.krig.assembly.AcquisitionCircuitBreakerPolicy
import space.kscience.krig.assembly.AcquisitionConnectors
import space.kscience.krig.assembly.AcquisitionFaultTypes
import space.kscience.krig.assembly.AcquisitionSourceReader
import space.kscience.krig.assembly.dataAcquisition
import space.kscience.krig.assembly.pollTimer
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

internal data class DeadPlcCircuitBreakerSnapshot(
    val connectorAttempts: Int,
    val firstFaultType: Name?,
    val secondFaultType: Name?,
    val secondQualitySeverity: QualitySeverity,
)

/** Source-level circuit breaker for a dead PLC-style acquisition connector. */
suspend fun deadPlcCircuitBreakerDemo() {
    val snapshot = deadPlcCircuitBreakerSnapshot()

    println("=== Dead PLC circuit breaker ===")
    println("  connector attempts: ${snapshot.connectorAttempts}")
    println("  first fault: ${snapshot.firstFaultType}")
    println("  second fault: ${snapshot.secondFaultType}")
    println("  second quality: ${snapshot.secondQualitySeverity.label}")
    println("\nDone - dead PLC circuit breaker demo complete.")
}

internal suspend fun deadPlcCircuitBreakerSnapshot(): DeadPlcCircuitBreakerSnapshot {
    val config = dataAcquisition {
        source(
            id = "plcA",
            connector = AcquisitionConnectors.KrigDevice,
            circuitBreaker = AcquisitionCircuitBreakerPolicy(failureThreshold = 1, resetTimeoutMs = 60_000),
        )
        tag("plcA.rpm").from("plcA", "drive.rpm", TypeIds.DOUBLE, timeout = 50.milliseconds)
        timer("fast", 10.milliseconds) { samples("plcA.rpm") }
    }
    var connectorAttempts = 0
    val reader = AcquisitionSourceReader { _, tags ->
        connectorAttempts += 1
        tags.associate { tag ->
            tag.id to OperationOutcome.Fail(
                TransportFault(
                    causeType = "DemoDeadPlc",
                    message = "PLC did not respond.",
                ),
            )
        }
    }
    val observations = config.pollTimer(
        timerId = "fast",
        ticks = flowOf(Unit, Unit),
        reader = reader,
        clock = FixedDemoClock,
    ).toList()

    return DeadPlcCircuitBreakerSnapshot(
        connectorAttempts = connectorAttempts,
        firstFaultType = observations[0].fault?.faultType,
        secondFaultType = observations[1].fault?.faultType,
        secondQualitySeverity = observations[1].observed.quality.severity,
    ).also {
        check(it.secondFaultType == AcquisitionFaultTypes.CircuitOpen)
    }
}

private object FixedDemoClock : Clock {
    override fun now(): Instant = Instant.fromEpochMilliseconds(1_000)
}
