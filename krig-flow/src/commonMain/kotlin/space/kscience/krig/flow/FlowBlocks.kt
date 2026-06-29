package space.kscience.krig.flow

import space.kscience.dataforge.names.Name

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
