package space.kscience.krig.assembly

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.withTimeout
import kotlinx.io.IOException
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName
import space.kscience.dataforge.meta.Meta
import space.kscience.krig.api.data.DataQuality
import space.kscience.krig.api.data.DefaultQualityPolicy
import space.kscience.krig.api.data.ObservedValue
import space.kscience.krig.api.data.QualityPolicy
import space.kscience.krig.api.data.QualityNamespaces
import space.kscience.krig.api.faults.OperationFault
import space.kscience.krig.api.faults.OperationFaultException
import space.kscience.krig.api.faults.TimeoutFault
import space.kscience.krig.api.faults.TransportFault
import space.kscience.krig.api.result.OperationOutcome
import space.kscience.krig.api.result.fail
import space.kscience.krig.api.data.toDataQuality
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds

/**
 * Protocol-neutral reader used by external acquisition integrations. The SDK owns
 * scheduling and mapping; the integration owns connector state and address syntax.
 */
public fun interface AcquisitionTagReader {
    public suspend fun read(tag: AcquisitionTagSpec): OperationOutcome<ObservedValue<Meta?>>
}

/**
 * Builds an [AcquisitionTagReader] for integrations that can return a [Meta] sample
 * directly. Use the function interface when the adapter owns protocol-level quality
 * or needs to return [OperationOutcome.Fail][OperationOutcome.Fail].
 */
public fun acquisitionTagReader(
    clock: Clock = Clock.System,
    quality: DataQuality = DataQuality.GOOD,
    read: suspend (AcquisitionTagSpec) -> Meta?,
): AcquisitionTagReader = AcquisitionTagReader { tag ->
    OperationOutcome.Ok(ObservedValue(value = read(tag), time = clock.now(), quality = quality))
}

/** Builds an [AcquisitionTagReader] from a source that already returns observed samples. */
public fun observedAcquisitionTagReader(
    read: suspend (AcquisitionTagSpec) -> ObservedValue<Meta?>,
): AcquisitionTagReader = AcquisitionTagReader { tag ->
    OperationOutcome.Ok(read(tag))
}

/** One polling result for a configured acquisition tag. */
public data class AcquisitionObservation(
    public val tag: AcquisitionTagSpec,
    public val observed: ObservedValue<Meta?>,
    public val fault: OperationFault? = null,
) {
    public val isOk: Boolean get() = fault == null
}

/** Resolves the configured tags sampled by [timerId], preserving timer order. */
public fun DataAcquisitionConfiguration.tagsForTimer(timerId: Name): List<AcquisitionTagSpec> {
    val timer = timers.singleOrNull { it.id == timerId }
        ?: error("Acquisition timer '$timerId' is not declared.")
    val tagsById = tags.associateBy { it.id }
    return timer.tags.map { tagId ->
        tagsById[tagId] ?: error("Acquisition timer '$timerId' references unknown tag '$tagId'.")
    }
}

public fun DataAcquisitionConfiguration.tagsForTimer(timerId: String): List<AcquisitionTagSpec> =
    tagsForTimer(timerId.asName())

/**
 * Polls all tags attached to [timerId] whenever [ticks] emits. Timeouts declared
 * on [AcquisitionTagSpec.timeoutMs] become degraded observations instead of
 * cancelling the whole polling loop. Parent cancellation still propagates.
 */
public fun DataAcquisitionConfiguration.pollTimer(
    timerId: Name,
    ticks: Flow<Unit>,
    clock: Clock = Clock.System,
    reader: AcquisitionTagReader,
    qualityPolicy: QualityPolicy = DefaultQualityPolicy,
): Flow<AcquisitionObservation> {
    val timerTags = tagsForTimer(timerId)
    return ticks.transform {
        for (tag in timerTags) {
            emit(reader.observe(tag, clock, qualityPolicy))
        }
    }
}

public fun DataAcquisitionConfiguration.pollTimer(
    timerId: String,
    ticks: Flow<Unit>,
    clock: Clock = Clock.System,
    reader: AcquisitionTagReader,
    qualityPolicy: QualityPolicy = DefaultQualityPolicy,
): Flow<AcquisitionObservation> = pollTimer(timerId.asName(), ticks, clock, reader, qualityPolicy)

private suspend fun AcquisitionTagReader.observe(
    tag: AcquisitionTagSpec,
    clock: Clock,
    qualityPolicy: QualityPolicy,
): AcquisitionObservation = when (val outcome = readWithTimeout(tag)) {
    is OperationOutcome.Ok -> AcquisitionObservation(
        tag = tag,
        observed = outcome.value,
    )
    is OperationOutcome.Fail -> AcquisitionObservation(
        tag = tag,
        observed = ObservedValue(
            value = null,
            time = clock.now(),
            quality = outcome.fault.toDataQuality(QualityNamespaces.Acquisition, qualityPolicy),
        ),
        fault = outcome.fault,
    )
}

private suspend fun AcquisitionTagReader.readWithTimeout(tag: AcquisitionTagSpec): OperationOutcome<ObservedValue<Meta?>> = try {
    val timeoutMs = tag.timeoutMs
    if (timeoutMs == null) {
        read(tag)
    } else {
        withTimeout(timeoutMs.milliseconds) { read(tag) }
    }
} catch (_: TimeoutCancellationException) {
    fail(TimeoutFault())
} catch (e: CancellationException) {
    throw e
} catch (e: OperationFaultException) {
    fail(e.fault)
} catch (e: IOException) {
    fail(
        TransportFault(
            causeType = e::class.simpleName ?: "IOException",
            message = e.message ?: "I/O failure while reading acquisition tag '${tag.id}'.",
        )
    )
}
