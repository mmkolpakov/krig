package space.kscience.krig.assembly

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout
import kotlinx.io.IOException
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName
import space.kscience.dataforge.names.parseAsName
import space.kscience.krig.api.data.DataQuality
import space.kscience.krig.api.data.DefaultQualityPolicy
import space.kscience.krig.api.data.ObservedValue
import space.kscience.krig.api.data.QualityNamespaces
import space.kscience.krig.api.data.QualityPolicy
import space.kscience.krig.api.data.toDataQuality
import space.kscience.krig.api.faults.GenericOperationFault
import space.kscience.krig.api.faults.OperationFault
import space.kscience.krig.api.faults.OperationFaultDetails
import space.kscience.krig.api.faults.OperationFaultException
import space.kscience.krig.api.faults.TimeoutFault
import space.kscience.krig.api.faults.TransportFault
import space.kscience.krig.api.result.OperationOutcome
import space.kscience.krig.api.result.fail
import space.kscience.krig.core.contracts.Device
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds

/**
 * Reads every tag of one source in a single batch, keyed by [AcquisitionTagSpec.id]. This is the
 * canonical integration point: connectors that read several addresses at once (device trees, batch
 * PLCs) keep that efficiency, while simpler per-address connectors adapt through [bySource].
 */
public fun interface AcquisitionSourceReader {
    public suspend fun readSource(
        source: AcquisitionSourceSpec,
        tags: List<AcquisitionTagSpec>,
    ): Map<Name, OperationOutcome<ObservedValue<Meta?>>>
}

/** Reads one tag at a time. Use when a connector has no batch surface. */
public fun interface AcquisitionTagReader {
    public suspend fun read(tag: AcquisitionTagSpec): OperationOutcome<ObservedValue<Meta?>>
}

/**
 * Builds an [AcquisitionTagReader] for integrations returning a [Meta] sample directly. Use the
 * interface when the adapter owns protocol-level quality or returns [OperationOutcome.Fail].
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

/** Adapts a per-tag reader to the batch surface, honouring each tag's [timeoutMs][AcquisitionTagSpec.timeoutMs]. */
public fun AcquisitionTagReader.bySource(): AcquisitionSourceReader =
    AcquisitionSourceReader { _, tags -> tags.associate { tag -> tag.id to readWithTimeout(tag) } }

/** Dispatches each source to the reader registered for its [connector][AcquisitionSourceSpec.connector]. */
public fun connectorAcquisitionReader(
    readers: Map<Name, AcquisitionSourceReader>,
): AcquisitionSourceReader = AcquisitionSourceReader { source, tags ->
    val reader = readers[source.connector]
        ?: error("No acquisition reader registered for connector '${source.connector}'.")
    reader.readSource(source, tags)
}

/**
 * Reader for [AcquisitionConnectors.KrigDevice] sources: resolves the device by explicit
 * [AcquisitionSourceSpec.topologyPath] when present, or by the named single-token source-id
 * convention otherwise, and reads the tagged properties in one
 * [readBatchOutcome][Device.readBatchOutcome] call.
 *
 * Per-tag [timeoutMs][AcquisitionTagSpec.timeoutMs] is projected through
 * [AcquisitionSourceSpec.batchTimeoutPolicy] because one coalesced read cannot be cancelled per tag.
 * On expiry all tags in the batch fail with [TimeoutFault]. Tags without a timeout do not contribute
 * a bound unless the connector applies one in [AcquisitionSourceSpec.config].
 */
public fun deviceTreeAcquisitionReader(
    devices: Map<Name, Device>,
): AcquisitionSourceReader = AcquisitionSourceReader { source, tags ->
    val device = source.deviceLookupKeys().firstNotNullOfOrNull(devices::get)
        ?: return@AcquisitionSourceReader tags.failAll(
            GenericOperationFault(message = "Unknown device-tree source '${source.id}'."),
        )
    // For the krig.device connector the address is a property name in the device tree — i.e.
    // semantically hierarchical ("engine.rpm" reads `rpm` from child `engine`), so it is parsed
    // by dots rather than taken as a single token.
    val addresses = tags.map { it.address.parseAsName() }
    val batchTimeoutMs = source.batchTimeoutPolicy.resolveBatchTimeoutMs(tags)
    val outcomes = try {
        if (batchTimeoutMs == null) {
            device.readBatchOutcome(addresses)
        } else {
            withTimeout(batchTimeoutMs.milliseconds) { device.readBatchOutcome(addresses) }
        }
    } catch (_: TimeoutCancellationException) {
        return@AcquisitionSourceReader tags.failAll(
            TimeoutFault(operation = source.id, budget = batchTimeoutMs?.milliseconds),
        )
    }
    tags.associate { tag ->
        tag.id to (
            outcomes[tag.address.parseAsName()] ?: fail(
                GenericOperationFault(
                    message = "Device '${source.id}' did not return property '${tag.address}'.",
                ),
            )
            )
    }
}

private fun AcquisitionSourceSpec.deviceLookupKeys(): List<Name> = buildList {
    topologyPath?.let(::add)
    add(id)
    val conventionPath = id.asAcquisitionTopologyPath()
    if (conventionPath != id) add(conventionPath)
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
 * Polls every tag attached to [timerId] whenever [ticks] emits, grouped per source so batch-capable
 * connectors read once per tick. Tag faults and source failures become degraded observations rather
 * than cancelling the loop; parent cancellation still propagates. Emission preserves timer order.
 *
 * Configuration mismatches (unknown timer, tag, or source reference) are validated **here**, at
 * flow construction — a config error must fail fast at wiring time, not kill the polling loop on
 * its first tick.
 */
public fun DataAcquisitionConfiguration.pollTimer(
    timerId: Name,
    ticks: Flow<Unit>,
    reader: AcquisitionSourceReader,
    clock: Clock = Clock.System,
    qualityPolicy: QualityPolicy = DefaultQualityPolicy,
): Flow<SamplingObservation<AcquisitionTagSpec>> {
    val timerTags = tagsForTimer(timerId)
    val sourcesById = sources.associateBy { it.id }
    val tagsBySource: List<Pair<AcquisitionSourceSpec, List<AcquisitionTagSpec>>> =
        timerTags.groupBy { it.sourceId }.map { (sourceId, sourceTags) ->
            val source = sourcesById[sourceId]
                ?: error("Acquisition timer '$timerId' references unknown source '$sourceId'.")
            source to sourceTags
        }
    return flow {
        val circuitBreakers = tagsBySource.associate { (source, _) -> source.id to AcquisitionCircuitBreaker(source) }
        ticks.collect {
            val byTagId = LinkedHashMap<Name, SamplingObservation<AcquisitionTagSpec>>(timerTags.size)
            for ((source, sourceTags) in tagsBySource) {
                val outcomes = circuitBreakers.getValue(source.id).readOrFail(sourceTags, clock) {
                    reader.readSourceCatching(source, sourceTags)
                }
                sourceTags.forEach { tag ->
                    byTagId[tag.id] = tag.toObservation(outcomes[tag.id], clock, qualityPolicy)
                }
            }
            timerTags.forEach { tag -> emit(byTagId.getValue(tag.id)) }
        }
    }
}

public fun DataAcquisitionConfiguration.pollTimer(
    timerId: String,
    ticks: Flow<Unit>,
    reader: AcquisitionSourceReader,
    clock: Clock = Clock.System,
    qualityPolicy: QualityPolicy = DefaultQualityPolicy,
): Flow<SamplingObservation<AcquisitionTagSpec>> =
    pollTimer(timerId.asName(), ticks, reader, clock, qualityPolicy)

public fun DataAcquisitionConfiguration.pollTimer(
    timerId: Name,
    ticks: Flow<Unit>,
    reader: AcquisitionTagReader,
    clock: Clock = Clock.System,
    qualityPolicy: QualityPolicy = DefaultQualityPolicy,
): Flow<SamplingObservation<AcquisitionTagSpec>> =
    pollTimer(timerId, ticks, reader.bySource(), clock, qualityPolicy)

public fun DataAcquisitionConfiguration.pollTimer(
    timerId: String,
    ticks: Flow<Unit>,
    reader: AcquisitionTagReader,
    clock: Clock = Clock.System,
    qualityPolicy: QualityPolicy = DefaultQualityPolicy,
): Flow<SamplingObservation<AcquisitionTagSpec>> =
    pollTimer(timerId.asName(), ticks, reader.bySource(), clock, qualityPolicy)

/** Reads a source batch and converts non-cancellation connector failures to per-tag failures. */
public suspend fun AcquisitionSourceReader.readSourceCatching(
    source: AcquisitionSourceSpec,
    tags: List<AcquisitionTagSpec>,
): Map<Name, OperationOutcome<ObservedValue<Meta?>>> = try {
    readSource(source, tags)
} catch (e: CancellationException) {
    throw e
} catch (e: OperationFaultException) {
    tags.failAll(e.fault)
} catch (e: IOException) {
    tags.failAll(
        TransportFault(
            causeType = e::class.simpleName ?: "IOException",
            message = e.message ?: "I/O failure while sampling acquisition source '${source.id}'.",
        ),
    )
} catch (e: Exception) {
    // Any non-cancellation connector failure degrades the affected tags to BAD rather than killing
    // the polling loop. CancellationException is rethrown above; Error/OOM intentionally propagate.
    tags.failAll(
        GenericOperationFault(
            message = e.message ?: "Failure while sampling acquisition source '${source.id}'.",
        ),
    )
}

private suspend fun AcquisitionTagReader.readWithTimeout(
    tag: AcquisitionTagSpec,
): OperationOutcome<ObservedValue<Meta?>> = try {
    val timeoutMs = tag.timeoutMs
    if (timeoutMs == null) read(tag) else withTimeout(timeoutMs.milliseconds) { read(tag) }
} catch (_: TimeoutCancellationException) {
    fail(TimeoutFault(operation = tag.id, budget = tag.timeoutMs?.milliseconds))
} catch (e: CancellationException) {
    throw e
} catch (e: OperationFaultException) {
    fail(e.fault)
} catch (e: IOException) {
    fail(
        TransportFault(
            causeType = e::class.simpleName ?: "IOException",
            message = e.message ?: "I/O failure while reading acquisition tag '${tag.id}'.",
        ),
    )
}

private fun AcquisitionTagSpec.toObservation(
    outcome: OperationOutcome<ObservedValue<Meta?>>?,
    clock: Clock,
    qualityPolicy: QualityPolicy,
): SamplingObservation<AcquisitionTagSpec> = when (outcome) {
    is OperationOutcome.Ok -> SamplingObservation(spec = this, observed = outcome.value)
    is OperationOutcome.Fail -> failed(clock, outcome.fault, qualityPolicy)
    null -> failed(
        clock,
        GenericOperationFault(message = "No acquisition reader produced a result for tag '$id'."),
        qualityPolicy,
    )
}

private fun AcquisitionTagSpec.failed(
    clock: Clock,
    fault: OperationFault,
    qualityPolicy: QualityPolicy,
): SamplingObservation<AcquisitionTagSpec> = SamplingObservation(
    spec = this,
    observed = ObservedValue(
        value = null,
        time = clock.now(),
        quality = fault.toDataQuality(QualityNamespaces.Acquisition, qualityPolicy),
    ),
    fault = fault,
)

private fun List<AcquisitionTagSpec>.failAll(
    fault: OperationFault,
): Map<Name, OperationOutcome<ObservedValue<Meta?>>> = associate { it.id to fail(fault) }

private class AcquisitionCircuitBreaker(source: AcquisitionSourceSpec) {
    private val sourceId: Name = source.id
    private val policy: AcquisitionCircuitBreakerPolicy = source.circuitBreaker
    private var state: AcquisitionCircuitState = AcquisitionCircuitState.Closed
    private var consecutiveFailures: Int = 0
    private var openedAtMs: Long = 0

    suspend fun readOrFail(
        tags: List<AcquisitionTagSpec>,
        clock: Clock,
        read: suspend () -> Map<Name, OperationOutcome<ObservedValue<Meta?>>>,
    ): Map<Name, OperationOutcome<ObservedValue<Meta?>>> {
        if (!policy.enabled) return read()

        val nowMs = clock.now().toEpochMilliseconds()
        if (state == AcquisitionCircuitState.Open) {
            val elapsedMs = nowMs - openedAtMs
            if (elapsedMs >= policy.resetTimeoutMs) {
                state = AcquisitionCircuitState.HalfOpen
            } else {
                return tags.failAll(circuitOpenFault(elapsedMs))
            }
        }

        val outcomes = read()
        if (outcomes.isSourceFailure(tags)) {
            onFailure(nowMs)
        } else {
            close()
        }
        return outcomes
    }

    private fun onFailure(nowMs: Long) {
        consecutiveFailures += 1
        if (state == AcquisitionCircuitState.HalfOpen || consecutiveFailures >= policy.failureThreshold) {
            state = AcquisitionCircuitState.Open
            openedAtMs = nowMs
            consecutiveFailures = 0
        }
    }

    private fun close() {
        state = AcquisitionCircuitState.Closed
        openedAtMs = 0
        consecutiveFailures = 0
    }

    private fun circuitOpenFault(elapsedMs: Long): GenericOperationFault =
        GenericOperationFault(
            faultType = AcquisitionFaultTypes.CircuitOpen,
            message = "Acquisition source '$sourceId' circuit is open.",
            details = Meta {
                OperationFaultDetails.MESSAGE put "Acquisition source '$sourceId' is skipped while its circuit is open."
                OperationFaultDetails.NAME put sourceId.toString()
                "state".asName() put state.name
                "elapsedMs".asName() put elapsedMs
                "resetTimeoutMs".asName() put policy.resetTimeoutMs
            },
        )
}

private fun Map<Name, OperationOutcome<ObservedValue<Meta?>>>.isSourceFailure(
    tags: List<AcquisitionTagSpec>,
): Boolean = tags.isNotEmpty() && tags.all { tag -> this[tag.id] is OperationOutcome.Fail }
