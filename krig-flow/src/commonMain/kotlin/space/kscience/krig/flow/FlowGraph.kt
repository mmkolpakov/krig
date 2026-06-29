package space.kscience.krig.flow

import space.kscience.dataforge.names.Name
import space.kscience.krig.api.data.DataQuality
import space.kscience.krig.api.data.QualityCode
import space.kscience.krig.api.data.QualitySeverity
import kotlin.math.min
import kotlin.time.Duration

/** Deterministic executable graph of flow blocks and declaration-ordered connections. */
public class FlowGraph internal constructor(
    public val blocks: Map<Name, FlowBlockSpec>,
    public val connections: List<FlowConnection>,
) {
    private val runtimeBlocks: Map<Name, RuntimeFlowBlock> = blocks.mapValues { (_, spec) -> spec.toRuntime() }
    private var latestStepReport: FlowStepReport? = null

    /** Last report produced by [step] or [advance], or `null` before the graph is advanced. */
    public val lastStepReport: FlowStepReport?
        get() = latestStepReport

    init {
        require(blocks.isNotEmpty()) { "Flow graph must contain at least one block" }
    }

    /** Executes one deterministic tick and returns transfer details plus the new snapshot. */
    public fun step(dt: Duration): FlowStepReport {
        require(dt.isPositive()) { "Flow step duration must be positive" }
        val seconds = dt.inWholeNanoseconds.toDouble() / NANOS_IN_SECOND
        runtimeBlocks.values.forEach { it.beginStep(seconds) }
        val transfers = connections.map { connection ->
            val source = runtimeBlocks.getValue(connection.source.blockId)
            val target = runtimeBlocks.getValue(connection.target.blockId)
            val amount = min(
                source.available(connection.source.portId),
                target.remainingInput(connection.target.portId),
            )
            val taken = source.take(connection.source.portId, amount)
            val accepted = target.accept(connection.target.portId, taken)
            FlowTransfer(connection, FlowAmount(accepted))
        }
        return FlowStepReport(dt = dt, transfers = transfers, snapshot = snapshot()).also { latestStepReport = it }
    }

    /** Advances the graph when a caller needs scheduler semantics and not the per-tick report. */
    public fun advance(dt: Duration) {
        latestStepReport = step(dt)
    }

    /** Returns a stable snapshot without advancing the graph. */
    public fun snapshot(): FlowGraphSnapshot = FlowGraphSnapshot(runtimeBlocks.mapValues { (_, block) -> block.snapshot() })

    private companion object {
        const val NANOS_IN_SECOND: Double = 1_000_000_000.0
    }
}

/** One tick result of [FlowGraph.step]. */
public data class FlowStepReport(
    public val dt: Duration,
    public val transfers: List<FlowTransfer>,
    public val snapshot: FlowGraphSnapshot,
)

/** Material transfer along a graph connection during one tick. */
public data class FlowTransfer(
    public val connection: FlowConnection,
    public val amount: FlowAmount,
)

/** Snapshot of all runtime block states. */
public data class FlowGraphSnapshot(
    public val blocks: Map<Name, FlowBlockSnapshot>,
)

/** Snapshot of one runtime block. */
public data class FlowBlockSnapshot(
    public val id: Name,
    public val kind: String,
    public val inventory: FlowAmount? = null,
    public val totalProduced: FlowAmount = FlowAmount.ZERO,
    public val totalConsumed: FlowAmount = FlowAmount.ZERO,
    public val lastInput: FlowAmount = FlowAmount.ZERO,
    public val lastOutput: FlowAmount = FlowAmount.ZERO,
    public val quality: DataQuality = DataQuality.GOOD,
)

/** Quality codes emitted by the flow engine. */
public object FlowQualities {
    public val EmptyBuffer: DataQuality = DataQuality(
        severity = QualitySeverity.UNCERTAIN,
        code = QualityCode("krig.flow.empty-buffer"),
        detail = "buffer is empty",
    )

    public val DelayedMaterial: DataQuality = DataQuality(
        severity = QualitySeverity.UNCERTAIN,
        code = QualityCode("krig.flow.delayed-material"),
        detail = "material is waiting in a delay line",
    )
}

private interface RuntimeFlowBlock {
    fun beginStep(seconds: Double)
    fun available(portId: Name): Double
    fun remainingInput(portId: Name): Double
    fun take(portId: Name, amount: Double): Double
    fun accept(portId: Name, amount: Double): Double
    fun snapshot(): FlowBlockSnapshot
}

private fun FlowBlockSpec.toRuntime(): RuntimeFlowBlock = when (this) {
    is FlowBufferSpec -> RuntimeBuffer(this)
    is FlowConsumerSpec -> RuntimeConsumer(this)
    is FlowDelayedSpec -> RuntimeDelayed(this)
    is FlowLimitedSpec -> RuntimeLimited(this)
    is FlowMixSpec -> RuntimeMix(this)
    is FlowProducerSpec -> RuntimeProducer(this)
    is FlowReactionSpec -> RuntimeReaction(this)
    is FlowSeparateSpec -> RuntimeSeparate(this)
}

private class RuntimeProducer(private val spec: FlowProducerSpec) : RuntimeFlowBlock {
    private var remaining = 0.0
    private var lastProduced = 0.0
    private var totalProduced = 0.0

    override fun beginStep(seconds: Double) {
        remaining = spec.productionRate.valuePerSecond * seconds
        lastProduced = 0.0
    }

    override fun available(portId: Name): Double = if (portId == spec.output.id) remaining else 0.0

    override fun remainingInput(portId: Name): Double = 0.0

    override fun take(portId: Name, amount: Double): Double {
        if (portId != spec.output.id) return 0.0
        val taken = amount.coerceIn(0.0, remaining)
        remaining -= taken
        lastProduced += taken
        totalProduced += taken
        return taken
    }

    override fun accept(portId: Name, amount: Double): Double = 0.0

    override fun snapshot(): FlowBlockSnapshot = FlowBlockSnapshot(
        id = spec.id,
        kind = "producer",
        totalProduced = FlowAmount(totalProduced),
        lastOutput = FlowAmount(lastProduced),
    )
}

private class RuntimeConsumer(private val spec: FlowConsumerSpec) : RuntimeFlowBlock {
    private var remaining = 0.0
    private var lastConsumed = 0.0
    private var totalConsumed = 0.0

    override fun beginStep(seconds: Double) {
        remaining = spec.capacity.valuePerSecond * seconds
        lastConsumed = 0.0
    }

    override fun available(portId: Name): Double = 0.0

    override fun remainingInput(portId: Name): Double = if (portId == spec.input.id) remaining else 0.0

    override fun take(portId: Name, amount: Double): Double = 0.0

    override fun accept(portId: Name, amount: Double): Double {
        if (portId != spec.input.id) return 0.0
        val accepted = amount.coerceIn(0.0, remaining)
        remaining -= accepted
        lastConsumed += accepted
        totalConsumed += accepted
        return accepted
    }

    override fun snapshot(): FlowBlockSnapshot = FlowBlockSnapshot(
        id = spec.id,
        kind = "consumer",
        totalConsumed = FlowAmount(totalConsumed),
        lastInput = FlowAmount(lastConsumed),
    )
}

private class RuntimeBuffer(private val spec: FlowBufferSpec) : RuntimeFlowBlock {
    private var inventory = spec.initial.value
    private var inputRemaining = 0.0
    private var outputRemaining = 0.0
    private var lastInput = 0.0
    private var lastOutput = 0.0

    override fun beginStep(seconds: Double) {
        inputRemaining = spec.inputLimit?.valuePerSecond?.times(seconds) ?: Double.POSITIVE_INFINITY
        outputRemaining = spec.outputLimit?.valuePerSecond?.times(seconds) ?: Double.POSITIVE_INFINITY
        lastInput = 0.0
        lastOutput = 0.0
    }

    override fun available(portId: Name): Double {
        if (portId != spec.output.id) return 0.0
        return min(inventory, outputRemaining)
    }

    override fun remainingInput(portId: Name): Double {
        if (portId != spec.input.id) return 0.0
        return min(spec.capacity.value - inventory, inputRemaining)
    }

    override fun take(portId: Name, amount: Double): Double {
        if (portId != spec.output.id) return 0.0
        val taken = amount.coerceIn(0.0, available(portId))
        inventory -= taken
        outputRemaining -= taken
        lastOutput += taken
        return taken
    }

    override fun accept(portId: Name, amount: Double): Double {
        if (portId != spec.input.id) return 0.0
        val accepted = amount.coerceIn(0.0, remainingInput(portId))
        inventory += accepted
        inputRemaining -= accepted
        lastInput += accepted
        return accepted
    }

    override fun snapshot(): FlowBlockSnapshot = FlowBlockSnapshot(
        id = spec.id,
        kind = "buffer",
        inventory = FlowAmount(inventory),
        lastInput = FlowAmount(lastInput),
        lastOutput = FlowAmount(lastOutput),
        quality = if (inventory > 0.0) DataQuality.GOOD else FlowQualities.EmptyBuffer,
    )
}

private class RuntimeLimited(private val spec: FlowLimitedSpec) : RuntimeFlowBlock {
    private var inventory = 0.0
    private var inputRemaining = 0.0
    private var outputRemaining = 0.0
    private var lastInput = 0.0
    private var lastOutput = 0.0

    override fun beginStep(seconds: Double) {
        inputRemaining = spec.inputLimit?.valuePerSecond?.times(seconds) ?: Double.POSITIVE_INFINITY
        outputRemaining = spec.outputLimit.valuePerSecond * seconds
        lastInput = 0.0
        lastOutput = 0.0
    }

    override fun available(portId: Name): Double {
        if (portId != spec.output.id) return 0.0
        return min(inventory, outputRemaining)
    }

    override fun remainingInput(portId: Name): Double {
        if (portId != spec.input.id) return 0.0
        return inputRemaining
    }

    override fun take(portId: Name, amount: Double): Double {
        if (portId != spec.output.id) return 0.0
        val taken = amount.coerceIn(0.0, available(portId))
        inventory = (inventory - taken).coerceAtLeast(0.0)
        outputRemaining -= taken
        lastOutput += taken
        return taken
    }

    override fun accept(portId: Name, amount: Double): Double {
        if (portId != spec.input.id) return 0.0
        val accepted = amount.coerceIn(0.0, inputRemaining)
        inventory += accepted
        inputRemaining -= accepted
        lastInput += accepted
        return accepted
    }

    override fun snapshot(): FlowBlockSnapshot = FlowBlockSnapshot(
        id = spec.id,
        kind = "limited",
        inventory = FlowAmount(inventory),
        lastInput = FlowAmount(lastInput),
        lastOutput = FlowAmount(lastOutput),
    )
}

private class RuntimeDelayed(private val spec: FlowDelayedSpec) : RuntimeFlowBlock {
    private val buckets = DoubleArray(spec.delaySteps + 1)
    private var currentIndex = 0
    private var outputRemaining = 0.0
    private var lastInput = 0.0
    private var lastOutput = 0.0

    override fun beginStep(seconds: Double) {
        currentIndex = (currentIndex + 1) % buckets.size
        outputRemaining = spec.outputLimit?.valuePerSecond?.times(seconds) ?: Double.POSITIVE_INFINITY
        lastInput = 0.0
        lastOutput = 0.0
    }

    override fun available(portId: Name): Double {
        if (portId != spec.output.id) return 0.0
        return min(buckets[currentIndex], outputRemaining)
    }

    override fun remainingInput(portId: Name): Double =
        if (portId == spec.input.id) Double.POSITIVE_INFINITY else 0.0

    override fun take(portId: Name, amount: Double): Double {
        if (portId != spec.output.id) return 0.0
        val taken = amount.coerceIn(0.0, available(portId))
        buckets[currentIndex] = (buckets[currentIndex] - taken).coerceAtLeast(0.0)
        outputRemaining -= taken
        lastOutput += taken
        return taken
    }

    override fun accept(portId: Name, amount: Double): Double {
        if (portId != spec.input.id) return 0.0
        val accepted = amount.coerceAtLeast(0.0)
        buckets[acceptIndex()] += accepted
        lastInput += accepted
        return accepted
    }

    override fun snapshot(): FlowBlockSnapshot {
        val inventory = buckets.total()
        return FlowBlockSnapshot(
            id = spec.id,
            kind = "delayed",
            inventory = FlowAmount(inventory),
            lastInput = FlowAmount(lastInput),
            lastOutput = FlowAmount(lastOutput),
            quality = if (inventory > 0.0 && buckets[currentIndex] == 0.0) {
                FlowQualities.DelayedMaterial
            } else {
                DataQuality.GOOD
            },
        )
    }

    private fun acceptIndex(): Int = (currentIndex + spec.delaySteps) % buckets.size
}

private class RuntimeReaction(private val spec: FlowReactionSpec) : RuntimeFlowBlock {
    private var inputInventory = 0.0
    private var inputRemaining = 0.0
    private var lastInput = 0.0
    private var lastOutput = 0.0

    override fun beginStep(seconds: Double) {
        inputRemaining = spec.inputLimit?.valuePerSecond?.times(seconds) ?: Double.POSITIVE_INFINITY
        lastInput = 0.0
        lastOutput = 0.0
    }

    override fun available(portId: Name): Double {
        if (portId != spec.output.id) return 0.0
        return inputInventory * spec.yield.value
    }

    override fun remainingInput(portId: Name): Double =
        if (portId == spec.input.id) inputRemaining else 0.0

    override fun take(portId: Name, amount: Double): Double {
        if (portId != spec.output.id) return 0.0
        val taken = amount.coerceIn(0.0, available(portId))
        inputInventory = (inputInventory - taken / spec.yield.value).coerceAtLeast(0.0)
        lastOutput += taken
        return taken
    }

    override fun accept(portId: Name, amount: Double): Double {
        if (portId != spec.input.id) return 0.0
        val accepted = amount.coerceIn(0.0, inputRemaining)
        inputInventory += accepted
        inputRemaining -= accepted
        lastInput += accepted
        return accepted
    }

    override fun snapshot(): FlowBlockSnapshot = FlowBlockSnapshot(
        id = spec.id,
        kind = "reaction",
        inventory = FlowAmount(inputInventory),
        lastInput = FlowAmount(lastInput),
        lastOutput = FlowAmount(lastOutput),
    )
}

private class RuntimeSeparate(private val spec: FlowSeparateSpec) : RuntimeFlowBlock {
    private val outputIds: List<Name> = spec.outputs.map { it.port.id }
    private val outputIndex: Map<Name, Int> = outputIds.withIndex().associate { (index, id) -> id to index }
    private val shares: DoubleArray = spec.outputs.map { it.share.value }.toDoubleArray()
    private val inventories = DoubleArray(spec.outputs.size)
    private var lastInput = 0.0
    private var lastOutput = 0.0

    override fun beginStep(seconds: Double) {
        lastInput = 0.0
        lastOutput = 0.0
    }

    override fun available(portId: Name): Double {
        val index = outputIndex[portId] ?: return 0.0
        return inventories[index]
    }

    override fun remainingInput(portId: Name): Double =
        if (portId == spec.input.id) Double.POSITIVE_INFINITY else 0.0

    override fun take(portId: Name, amount: Double): Double {
        val index = outputIndex[portId] ?: return 0.0
        val taken = amount.coerceIn(0.0, inventories[index])
        inventories[index] = (inventories[index] - taken).coerceAtLeast(0.0)
        lastOutput += taken
        return taken
    }

    override fun accept(portId: Name, amount: Double): Double {
        if (portId != spec.input.id) return 0.0
        val accepted = amount.coerceAtLeast(0.0)
        for (index in shares.indices) {
            inventories[index] += accepted * shares[index]
        }
        lastInput += accepted
        return accepted
    }

    override fun snapshot(): FlowBlockSnapshot = FlowBlockSnapshot(
        id = spec.id,
        kind = "separate",
        inventory = FlowAmount(inventories.total()),
        lastInput = FlowAmount(lastInput),
        lastOutput = FlowAmount(lastOutput),
    )
}

private class RuntimeMix(private val spec: FlowMixSpec) : RuntimeFlowBlock {
    private var inventory = 0.0
    private var lastInput = 0.0
    private var lastOutput = 0.0
    private val inputIds = spec.inputs.map { it.id }.toSet()

    override fun beginStep(seconds: Double) {
        lastInput = 0.0
        lastOutput = 0.0
    }

    override fun available(portId: Name): Double = if (portId == spec.output.id) inventory else 0.0

    override fun remainingInput(portId: Name): Double = if (portId in inputIds) Double.POSITIVE_INFINITY else 0.0

    override fun take(portId: Name, amount: Double): Double {
        if (portId != spec.output.id) return 0.0
        val taken = amount.coerceIn(0.0, inventory)
        inventory -= taken
        lastOutput += taken
        return taken
    }

    override fun accept(portId: Name, amount: Double): Double {
        if (portId !in inputIds) return 0.0
        val accepted = amount.coerceAtLeast(0.0)
        inventory += accepted
        lastInput += accepted
        return accepted
    }

    override fun snapshot(): FlowBlockSnapshot = FlowBlockSnapshot(
        id = spec.id,
        kind = "mix",
        inventory = FlowAmount(inventory),
        lastInput = FlowAmount(lastInput),
        lastOutput = FlowAmount(lastOutput),
    )
}

private fun DoubleArray.total(): Double {
    var sum = 0.0
    for (value in this) sum += value
    return sum
}
