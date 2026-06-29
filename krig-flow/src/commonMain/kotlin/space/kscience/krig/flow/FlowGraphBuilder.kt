package space.kscience.krig.flow

import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName

/** Builder for deterministic continuous-flow graphs. */
public class FlowGraphBuilder {
    private val specs: MutableMap<Name, FlowBlockSpec> = linkedMapOf()
    private val connections: MutableList<FlowConnection> = mutableListOf()

    @IgnorableReturnValue
    public fun producer(
        id: Name,
        unit: FlowUnit,
        productionRate: FlowRate,
        output: Name = DefaultPorts.Output,
    ): FlowProducerSpec = add(
        FlowProducerSpec(id = id, output = FlowPort(output, unit), productionRate = productionRate),
    )

    @IgnorableReturnValue
    public fun producer(
        id: String,
        unit: FlowUnit,
        productionRate: FlowRate,
        output: String = "out",
    ): FlowProducerSpec = producer(id.asName(), unit, productionRate, output.asName())

    @IgnorableReturnValue
    public fun consumer(
        id: Name,
        unit: FlowUnit,
        capacity: FlowRate,
        input: Name = DefaultPorts.Input,
    ): FlowConsumerSpec = add(
        FlowConsumerSpec(id = id, input = FlowPort(input, unit), capacity = capacity),
    )

    @IgnorableReturnValue
    public fun consumer(
        id: String,
        unit: FlowUnit,
        capacity: FlowRate,
        input: String = "in",
    ): FlowConsumerSpec = consumer(id.asName(), unit, capacity, input.asName())

    @IgnorableReturnValue
    public fun buffer(
        id: Name,
        unit: FlowUnit,
        capacity: FlowAmount,
        initial: FlowAmount = FlowAmount.ZERO,
        inputLimit: FlowRate? = null,
        outputLimit: FlowRate? = null,
        input: Name = DefaultPorts.Input,
        output: Name = DefaultPorts.Output,
    ): FlowBufferSpec = add(
        FlowBufferSpec(
            id = id,
            input = FlowPort(input, unit),
            output = FlowPort(output, unit),
            capacity = capacity,
            initial = initial,
            inputLimit = inputLimit,
            outputLimit = outputLimit,
        ),
    )

    @IgnorableReturnValue
    public fun buffer(
        id: String,
        unit: FlowUnit,
        capacity: FlowAmount,
        initial: FlowAmount = FlowAmount.ZERO,
        inputLimit: FlowRate? = null,
        outputLimit: FlowRate? = null,
        input: String = "in",
        output: String = "out",
    ): FlowBufferSpec = buffer(
        id = id.asName(),
        unit = unit,
        capacity = capacity,
        initial = initial,
        inputLimit = inputLimit,
        outputLimit = outputLimit,
        input = input.asName(),
        output = output.asName(),
    )

    @IgnorableReturnValue
    public fun limited(
        id: Name,
        unit: FlowUnit,
        outputLimit: FlowRate,
        inputLimit: FlowRate? = null,
        input: Name = DefaultPorts.Input,
        output: Name = DefaultPorts.Output,
    ): FlowLimitedSpec = add(
        FlowLimitedSpec(
            id = id,
            input = FlowPort(input, unit),
            output = FlowPort(output, unit),
            outputLimit = outputLimit,
            inputLimit = inputLimit,
        ),
    )

    @IgnorableReturnValue
    public fun limited(
        id: String,
        unit: FlowUnit,
        outputLimit: FlowRate,
        inputLimit: FlowRate? = null,
        input: String = "in",
        output: String = "out",
    ): FlowLimitedSpec = limited(
        id = id.asName(),
        unit = unit,
        outputLimit = outputLimit,
        inputLimit = inputLimit,
        input = input.asName(),
        output = output.asName(),
    )

    @IgnorableReturnValue
    public fun delayed(
        id: Name,
        unit: FlowUnit,
        delaySteps: Int,
        outputLimit: FlowRate? = null,
        input: Name = DefaultPorts.Input,
        output: Name = DefaultPorts.Output,
    ): FlowDelayedSpec = add(
        FlowDelayedSpec(
            id = id,
            input = FlowPort(input, unit),
            output = FlowPort(output, unit),
            delaySteps = delaySteps,
            outputLimit = outputLimit,
        ),
    )

    @IgnorableReturnValue
    public fun delayed(
        id: String,
        unit: FlowUnit,
        delaySteps: Int,
        outputLimit: FlowRate? = null,
        input: String = "in",
        output: String = "out",
    ): FlowDelayedSpec = delayed(
        id = id.asName(),
        unit = unit,
        delaySteps = delaySteps,
        outputLimit = outputLimit,
        input = input.asName(),
        output = output.asName(),
    )

    @IgnorableReturnValue
    public fun conversion(
        id: Name,
        inputUnit: FlowUnit,
        outputUnit: FlowUnit = inputUnit,
        outputPerInput: FlowRatio = FlowRatio.ONE,
        inputLimit: FlowRate? = null,
        input: Name = DefaultPorts.Input,
        output: Name = DefaultPorts.Output,
    ): FlowConversionSpec {
        require(outputPerInput.value > 0.0) { "Conversion '$id' outputPerInput must be positive" }
        val productionLimit = inputLimit?.let { FlowRate(it.valuePerSecond * outputPerInput.value) }
        return add(
            FlowConversionSpec(
                id = id,
                inputs = listOf(FlowConversionInput(FlowPort(input, inputUnit), FlowRatio(1.0 / outputPerInput.value))),
                output = FlowPort(output, outputUnit),
                productionLimit = productionLimit,
            ),
        )
    }

    @IgnorableReturnValue
    public fun conversion(
        id: Name,
        unit: FlowUnit,
        inputCoefficients: Map<Name, FlowRatio>,
        productionLimit: FlowRate? = null,
        output: Name = DefaultPorts.Output,
    ): FlowConversionSpec = add(
        FlowConversionSpec(
            id = id,
            inputs = inputCoefficients.map { (port, coefficient) -> FlowConversionInput(FlowPort(port, unit), coefficient) },
            output = FlowPort(output, unit),
            productionLimit = productionLimit,
        ),
    )

    @IgnorableReturnValue
    public fun conversion(
        id: String,
        inputUnit: FlowUnit,
        outputUnit: FlowUnit = inputUnit,
        outputPerInput: FlowRatio = FlowRatio.ONE,
        inputLimit: FlowRate? = null,
        input: String = "in",
        output: String = "out",
    ): FlowConversionSpec = conversion(
        id = id.asName(),
        inputUnit = inputUnit,
        outputUnit = outputUnit,
        outputPerInput = outputPerInput,
        inputLimit = inputLimit,
        input = input.asName(),
        output = output.asName(),
    )

    @IgnorableReturnValue
    public fun conversion(
        id: String,
        unit: FlowUnit,
        inputCoefficients: Map<String, FlowRatio>,
        productionLimit: FlowRate? = null,
        output: String = "out",
    ): FlowConversionSpec = conversion(
        id = id.asName(),
        unit = unit,
        inputCoefficients = inputCoefficients.mapKeys { (port, _) -> port.asName() },
        productionLimit = productionLimit,
        output = output.asName(),
    )

    @IgnorableReturnValue
    public fun separate(
        id: Name,
        unit: FlowUnit,
        outputs: Map<Name, FlowRatio>,
        input: Name = DefaultPorts.Input,
    ): FlowSeparateSpec = add(
        FlowSeparateSpec(
            id = id,
            input = FlowPort(input, unit),
            outputs = outputs.map { (port, share) -> FlowSeparationOutput(FlowPort(port, unit), share) },
        ),
    )

    @IgnorableReturnValue
    public fun separate(
        id: String,
        unit: FlowUnit,
        outputs: Map<String, FlowRatio>,
        input: String = "in",
    ): FlowSeparateSpec = separate(
        id = id.asName(),
        unit = unit,
        outputs = outputs.mapKeys { (port, _) -> port.asName() },
        input = input.asName(),
    )

    @IgnorableReturnValue
    public fun mix(
        id: Name,
        unit: FlowUnit,
        inputs: List<Name>,
        output: Name = DefaultPorts.Output,
    ): FlowMixSpec = add(
        FlowMixSpec(
            id = id,
            inputs = inputs.map { FlowPort(it, unit) },
            output = FlowPort(output, unit),
        ),
    )

    @IgnorableReturnValue
    public fun mix(
        id: String,
        unit: FlowUnit,
        inputs: List<String>,
        output: String = "out",
    ): FlowMixSpec = mix(id.asName(), unit, inputs.map { it.asName() }, output.asName())

    @IgnorableReturnValue
    public fun connect(
        sourceBlock: Name,
        targetBlock: Name,
        sourcePort: Name = DefaultPorts.Output,
        targetPort: Name = DefaultPorts.Input,
    ): FlowConnection = FlowConnection(
        source = FlowEndpoint(sourceBlock, sourcePort),
        target = FlowEndpoint(targetBlock, targetPort),
    ).also { connections += it }

    @IgnorableReturnValue
    public fun connect(
        sourceBlock: String,
        targetBlock: String,
        sourcePort: String = "out",
        targetPort: String = "in",
    ): FlowConnection = connect(sourceBlock.asName(), targetBlock.asName(), sourcePort.asName(), targetPort.asName())

    public fun build(): FlowGraph {
        val declared = specs.toMap()
        val edges = connections.toList()
        for (connection in edges) validateConnection(declared, connection)
        return FlowGraph(declared, edges)
    }

    private fun <T : FlowBlockSpec> add(spec: T): T {
        require(spec.id !in specs) { "Flow block '${spec.id}' is already declared" }
        specs[spec.id] = spec
        return spec
    }

    private fun validateConnection(specs: Map<Name, FlowBlockSpec>, connection: FlowConnection) {
        val source = requireNotNull(specs[connection.source.blockId]) {
            "Source flow block '${connection.source.blockId}' is not declared"
        }
        val target = requireNotNull(specs[connection.target.blockId]) {
            "Target flow block '${connection.target.blockId}' is not declared"
        }
        val sourcePort = requireNotNull(source.outputPorts()[connection.source.portId]) {
            "Source port '${connection.source.portId}' is not declared on block '${source.id}'"
        }
        val targetPort = requireNotNull(target.inputPorts()[connection.target.portId]) {
            "Target port '${connection.target.portId}' is not declared on block '${target.id}'"
        }
        require(sourcePort.unit == targetPort.unit) {
            "Flow connection ${source.id}.${sourcePort.id} -> ${target.id}.${targetPort.id} has mismatched units: " +
                    "${sourcePort.unit} != ${targetPort.unit}"
        }
    }

    private object DefaultPorts {
        val Input: Name = "in".asName()
        val Output: Name = "out".asName()
    }
}

/** Builds a deterministic continuous-flow graph. */
public fun flowGraph(block: FlowGraphBuilder.() -> Unit): FlowGraph = FlowGraphBuilder().apply(block).build()

internal fun FlowBlockSpec.inputPorts(): Map<Name, FlowPort> = when (this) {
    is FlowBufferSpec -> mapOf(input.id to input)
    is FlowConsumerSpec -> mapOf(input.id to input)
    is FlowDelayedSpec -> mapOf(input.id to input)
    is FlowLimitedSpec -> mapOf(input.id to input)
    is FlowMixSpec -> inputs.associateBy { it.id }
    is FlowProducerSpec -> emptyMap()
    is FlowConversionSpec -> inputs.associate { it.port.id to it.port }
    is FlowSeparateSpec -> mapOf(input.id to input)
}

internal fun FlowBlockSpec.outputPorts(): Map<Name, FlowPort> = when (this) {
    is FlowBufferSpec -> mapOf(output.id to output)
    is FlowConsumerSpec -> emptyMap()
    is FlowDelayedSpec -> mapOf(output.id to output)
    is FlowLimitedSpec -> mapOf(output.id to output)
    is FlowMixSpec -> mapOf(output.id to output)
    is FlowProducerSpec -> mapOf(output.id to output)
    is FlowConversionSpec -> mapOf(output.id to output)
    is FlowSeparateSpec -> outputs.associate { it.port.id to it.port }
}
