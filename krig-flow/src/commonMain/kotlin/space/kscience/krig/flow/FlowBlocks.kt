package space.kscience.krig.flow

import space.kscience.dataforge.names.Name
import kotlin.math.abs

private const val FLOW_RATIO_TOLERANCE: Double = 1e-9

/** Declarative block in a [FlowGraph]. */
public sealed interface FlowBlockSpec {
    public val id: Name
}

/** Source block with a bounded production rate. */
public data class FlowProducerSpec(
    override val id: Name,
    public val output: FlowPort,
    public val productionRate: FlowRate,
) : FlowBlockSpec

/** Sink block with a bounded consumption rate. */
public data class FlowConsumerSpec(
    override val id: Name,
    public val input: FlowPort,
    public val capacity: FlowRate,
) : FlowBlockSpec

/** Storage block with finite capacity and optional input/output rate limits. */
public data class FlowBufferSpec(
    override val id: Name,
    public val input: FlowPort,
    public val output: FlowPort,
    public val capacity: FlowAmount,
    public val initial: FlowAmount = FlowAmount.ZERO,
    public val inputLimit: FlowRate? = null,
    public val outputLimit: FlowRate? = null,
) : FlowBlockSpec {
    init {
        require(input.unit == output.unit) { "Buffer '$id' input and output units must match" }
        require(initial <= capacity) { "Buffer '$id' initial amount must not exceed capacity" }
    }
}

/** Pass-through block with an output rate limit and optional input rate limit. */
public data class FlowLimitedSpec(
    override val id: Name,
    public val input: FlowPort,
    public val output: FlowPort,
    public val outputLimit: FlowRate,
    public val inputLimit: FlowRate? = null,
) : FlowBlockSpec {
    init {
        require(input.unit == output.unit) { "Limiter '$id' input and output units must match" }
    }
}

/** Delay line where accepted input becomes available after [delaySteps] graph ticks. */
public data class FlowDelayedSpec(
    override val id: Name,
    public val input: FlowPort,
    public val output: FlowPort,
    public val delaySteps: Int,
    public val outputLimit: FlowRate? = null,
) : FlowBlockSpec {
    init {
        require(input.unit == output.unit) { "Delay '$id' input and output units must match" }
        require(delaySteps > 0) { "Delay '$id' must be at least one step" }
    }
}

/** Single-input/single-output conversion; [yield] is output amount per input amount. */
public data class FlowReactionSpec(
    override val id: Name,
    public val input: FlowPort,
    public val output: FlowPort,
    public val yield: FlowRatio = FlowRatio.ONE,
    public val inputLimit: FlowRate? = null,
) : FlowBlockSpec {
    init {
        require(yield.value > 0.0) { "Reaction '$id' yield must be positive" }
    }
}

/** One output branch of [FlowSeparateSpec]. */
public data class FlowSeparationOutput(
    public val port: FlowPort,
    public val share: FlowRatio,
)

/** Splits one input into output branches with the same unit and normalized shares. */
public data class FlowSeparateSpec(
    override val id: Name,
    public val input: FlowPort,
    public val outputs: List<FlowSeparationOutput>,
) : FlowBlockSpec {
    init {
        require(outputs.isNotEmpty()) { "Separator '$id' must have at least one output" }
        require(outputs.map { it.port.id }.toSet().size == outputs.size) {
            "Separator '$id' output port ids must be unique"
        }
        require(outputs.all { it.port.unit == input.unit }) { "Separator '$id' input and output units must match" }
        require(outputs.all { it.share.value > 0.0 }) { "Separator '$id' shares must be positive" }
        require(abs(outputs.sumOf { it.share.value } - 1.0) <= FLOW_RATIO_TOLERANCE) {
            "Separator '$id' shares must sum to 1.0"
        }
    }
}

/** Combines several inputs with the same unit into one output. */
public data class FlowMixSpec(
    override val id: Name,
    public val inputs: List<FlowPort>,
    public val output: FlowPort,
) : FlowBlockSpec {
    init {
        require(inputs.isNotEmpty()) { "Mix '$id' must have at least one input" }
        require(inputs.map { it.id }.toSet().size == inputs.size) { "Mix '$id' input port ids must be unique" }
        require(inputs.all { it.unit == output.unit }) { "Mix '$id' input and output units must match" }
    }
}
